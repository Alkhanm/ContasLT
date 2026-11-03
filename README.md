# ContasLT

Controle financeiro pessoal no estilo Organizze, com foco em **contas recorrentes**, **categorização** e **importação de extratos (OFX e Excel)**. PWA instalável, tema claro/escuro com tendência ao verde escuro.

- **Frontend:** Vue 3 + TypeScript + Vite + Tailwind v4 + shadcn-vue (reka-ui) + Pinia + vite-plugin-pwa
- **Backend:** Spring Boot 4.1 (Java 21) + Spring Security (JWT HS256) + Spring Data JPA + Flyway + PostgreSQL
- **Importação:** parser OFX próprio (SGML/XML, moeda pt-BR) e Apache POI para `.xlsx/.xls`

## Rodando em desenvolvimento

Pré-requisitos: Java 21, Node 20+, Docker.

```bash
./dev.sh
```

O script sobe o banco (Docker), o backend (porta 8080) e o frontend (porta 5173, com proxy `/api` → 8080), instala as dependências do frontend se faltarem e encerra backend e frontend no Ctrl+C. Opções: `--backend-only`, `--frontend-only`, `--no-db` (banco já rodando) e `--install` (força `npm install`).

### Modo produção (uso no celular)

```bash
./prod.sh
```

Faz o build do frontend e do jar, sobe o banco e roda tudo numa porta só: o backend serve o frontend compilado em http://localhost:8585 (padrão; mude com `PORT` no `.env` ou no ambiente) (o script lista também os endereços da rede local e do Tailscale). Use este modo para acessar pelo celular: o servidor de desenvolvimento do Vite recarrega a página ao voltar da câmera (o WebSocket do HMR cai quando o navegador vai para segundo plano), o que fecha o formulário no meio da leitura do comprovante. Opções: `--skip-build` (reaproveita build e jar) e `--no-db`. Variáveis como `JWT_SECRET` ou `OLLAMA_MODEL` podem ficar num `.env` na raiz.

### Instalar como serviço (sobe no boot)

```bash
./install.sh              # ou ./install.sh --port 8090 para trocar a porta (fica gravada em .env)
```

Confere os pré-requisitos, gera um `JWT_SECRET` em `.env`, faz o build, copia jar e `dist` para `run/` e registra um serviço de usuário do systemd (`contaslt`), ligado no boot (com *linger*, não precisa de login) e sem sudo. O Postgres fica com `restart: unless-stopped` no Docker. Depois de mudar o código, rode `./install.sh` de novo: refaz o build e reinicia o serviço. A porta padrão é 8585; `PORT` no `.env` vale para `prod.sh` e para o serviço.

```bash
systemctl --user status contaslt      # estado
journalctl --user -u contaslt -f      # logs
systemctl --user restart contaslt     # reiniciar
./install.sh --uninstall              # remover o serviço (banco e data/ ficam)
```

Para subir cada parte separadamente:

```bash
docker compose up -d db                 # 1. Banco de dados
cd backend && ./mvnw spring-boot:run    # 2. Backend (porta 8080)
cd frontend && npm install && npm run dev   # 3. Frontend (porta 5173)
```

Abra http://localhost:5173, crie uma conta e pronto. O cadastro já cria categorias padrão e uma conta "Conta principal".

### Testes

```bash
cd backend && ./mvnw test        # unitários + fluxo completo da API (H2 em memória)
cd frontend && npm run build     # type-check (vue-tsc) + build PWA
```

### Variáveis de ambiente (backend)

| Variável | Padrão | Descrição |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `contaslt` | Endereço e nome do banco PostgreSQL |
| `DB_USER` / `DB_PASSWORD` | `contaslt` / `contaslt` | Credenciais do banco |
| `DB_SSLMODE` | `disable` | `sslmode` do JDBC; `require` no Supabase |
| `DB_POOL_MAX` / `DB_POOL_MIN` | `4` / `1` | Tamanho do pool de conexões (pequeno de propósito: pooler do Supabase) |
| `JWT_SECRET` | chave de desenvolvimento | **Obrigatória no profile `prod`** (mínimo 32 bytes) |
| `JWT_EXPIRATION` | `P7D` | Validade do token (ISO-8601 Duration) |
| `CORS_ORIGINS` | `http://localhost:[*]`, redes locais (`192.168.*`, `10.*`, `172.*`) e Tailscale (`100.*`), qualquer porta | Origens permitidas (padrões com `*` no host e `[*]` na porta). **Obrigatória no profile `prod`** |
| `PORT` | `8080` | Porta HTTP (o Railway injeta a dele) |
| `TOMCAT_THREADS` | `16` | Threads de requisição |
| `SPA_DIR` | vazio | Pasta com o build do frontend (`frontend/dist`); quando informada, o backend serve o frontend e devolve `index.html` para as rotas da aplicação |
| `RECEIPT_PROVIDER` | `ollama` (`worker` no profile `prod`) | Quem lê os comprovantes: `ollama` (local), `worker` (fila lida pelo computador do usuário, ver abaixo), `openai` (API compatível com OpenAI) ou `none` (desligado) |
| `WORKER_TOKEN` | vazio | Segredo do worker (`X-Worker-Token`) para as rotas `/api/worker/**`; vazio = rotas fechadas |
| `OLLAMA_URL` | `http://localhost:11434` | Servidor Ollama usado para ler comprovantes |
| `OLLAMA_MODEL` | `qwen3.5:9b-q8_0` | Modelo de visão (precisa da capacidade *vision*) |
| `OLLAMA_TIMEOUT` | `PT3M` | Tempo máximo de uma leitura |
| `OPENAI_URL` | `https://api.openai.com/v1` | Base da API compatível com OpenAI. Gemini: `https://generativelanguage.googleapis.com/v1beta/openai`; Groq: `https://api.groq.com/openai/v1`; OpenRouter: `https://openrouter.ai/api/v1` |
| `OPENAI_API_KEY` | vazio | Chave da API (obrigatória com `RECEIPT_PROVIDER=openai`) |
| `OPENAI_MODEL` | `gpt-4.1-mini` | Modelo com visão e *structured outputs* (ex.: `gemini-2.5-flash` no Gemini) |
| `OPENAI_TIMEOUT` | `PT2M` | Tempo máximo de uma leitura |
| `STORAGE_DIR` | `./data` | Pasta das imagens de comprovantes (`receipts/`), quando não há Supabase |
| `SUPABASE_URL` | vazio | URL do projeto Supabase (`https://<ref>.supabase.co`). Definida, os comprovantes vão para um bucket privado do Supabase Storage em vez do disco |
| `SUPABASE_SERVICE_KEY` | vazio | Chave `service_role` do projeto (o backend é o único cliente do bucket) |
| `SUPABASE_BUCKET` | `receipts` | Nome do bucket (criado na primeira gravação, se não existir) |
| `SPRING_PROFILES_ACTIVE` | vazio | `prod` em produção: exige `JWT_SECRET` e `CORS_ORIGINS`, desliga o Ollama por padrão e esconde mensagens internas de erro |

No frontend, `VITE_API_URL` define a base da API (vazio = mesma origem). O build de produção lê `frontend/.env.production`.

## Deploy (Railway + Supabase + Firebase Hosting)

Mesmo arranjo do Fimagion: o backend roda num container no **Railway** (plano pequeno: 0,5 vCPU e 1 GB), o banco e as imagens de comprovantes ficam no **Supabase** (Postgres + Storage) e o frontend é servido pelo **Firebase Hosting**. O browser fala com o backend direto, de outra origem, com o token JWT no header (não há cookie, então não há configuração de SameSite).

### 1. Supabase

1. Crie o projeto (região `South America (São Paulo)`), anote a senha do banco.
2. **Banco:** em *Connect* copie os dados do **Session pooler** (porta `5432`, host `aws-0-sa-east-1.pooler.supabase.com`, usuário `postgres.<ref>`). Use o pooler, e não a conexão direta: a direta só tem IPv6 no plano gratuito e o Railway sai por IPv4. O modo *session* é necessário porque o driver JDBC usa *prepared statements* (o modo *transaction*, porta `6543`, quebra isso). O Flyway cria as tabelas no schema `public` na primeira subida.
3. **Storage:** nada a fazer; o backend cria o bucket privado `receipts` na primeira foto. A chave `service_role` está em *Project Settings › API*.

### 2. Railway (backend)

1. *New Project › Deploy from GitHub repo*, escolha este repositório e, no serviço, defina **Settings › Source › Root Directory = `backend`**. O Railway encontra o `Dockerfile` e o `railway.json` (healthcheck em `/actuator/health`) nessa pasta e só refaz o deploy quando algo em `backend/` muda.
2. *Settings › Networking › Generate Domain* para ter a URL pública (ex.: `https://contaslt-production.up.railway.app`).
3. *Variables*:

```
SPRING_PROFILES_ACTIVE=prod
DB_HOST=aws-0-sa-east-1.pooler.supabase.com
DB_PORT=5432
DB_NAME=postgres
DB_USER=postgres.<ref>
DB_PASSWORD=<senha do banco>
DB_SSLMODE=require
JWT_SECRET=<openssl rand -base64 48>
CORS_ORIGINS=https://contaslt.web.app,https://contaslt.firebaseapp.com
SUPABASE_URL=https://<ref>.supabase.co
SUPABASE_SERVICE_KEY=<service_role>
# Leitura de comprovantes pelo worker do seu computador (padrão em prod): o mesmo segredo vai no .env do PC.
WORKER_TOKEN=<openssl rand -base64 32>
# Alternativa sem o PC: RECEIPT_PROVIDER=openai + OPENAI_API_KEY (OPENAI_URL/OPENAI_MODEL para Gemini, Groq...)
```

A imagem já vem com a JVM ajustada para 1 GB (`JAVA_TOOL_OPTIONS` no `Dockerfile`: heap em 60% da memória, SerialGC, só o compilador C1, arquivo CDS gerado no build). Para mudar, sobrescreva `JAVA_TOOL_OPTIONS` nas variáveis. A subida leva cerca de um minuto nesse tamanho de máquina; o healthcheck espera até 5.

Para testar a imagem localmente, simulando a máquina do Railway:

```bash
cd backend && docker build -t contaslt-backend .
docker run --rm --network host --memory 1g --cpus 0.5 -e PORT=8590 -e SPRING_PROFILES_ACTIVE=prod \
  -e JWT_SECRET=$(openssl rand -base64 48) -e CORS_ORIGINS='http://localhost:[*]' contaslt-backend
```

### Leitura de comprovantes em produção: o worker no seu computador

O servidor não tem modelo de visão. Com `RECEIPT_PROVIDER=worker` (padrão no profile `prod`), "Ler comprovante" guarda a foto no Supabase e cria uma **tarefa**; a resposta volta na hora com a foto anexada, e o app consulta a tarefa enquanto o computador responde. Um serviço no seu PC, o **worker**, puxa as tarefas do servidor, lê cada foto com o Ollama local e devolve o resultado. Nada entra no seu computador: o worker só faz chamadas de saída (HTTPS para o Railway, HTTP para o Ollama em `localhost`), e se autentica com um segredo próprio, separado do login dos usuários.

- **Computador ligado:** o formulário espera e é preenchido como sempre (uns 25 s).
- **Computador desligado:** o app avisa, você salva o lançamento normalmente com a foto anexada. Quando o PC ligar, o worker lê a foto e os itens, o estabelecimento e a chave do cupom entram sozinhos no lançamento (sem sobrescrever o que você digitou). Se o total ou a data do cupom não baterem com o lançado, o lançamento mostra um aviso com "Usar o do comprovante" / "Manter o lançado". Se a leitura falhar três vezes, aparece "Tentar de novo".

Instalação, no PC com o Ollama:

```bash
openssl rand -base64 32          # gere o segredo e cadastre como WORKER_TOKEN no Railway
printf 'CONTASLT_URL=https://contaslt-production.up.railway.app\nWORKER_TOKEN=<o mesmo segredo>\n' >> .env
./install.sh --worker            # serviço de usuário contaslt-worker, sobe no boot
journalctl --user -u contaslt-worker -f
```

O worker é o mesmo jar do backend rodando com `--worker` (sem Spring, sem banco, ~150 MB de RAM). Variáveis: `CONTASLT_URL`, `WORKER_TOKEN`, `OLLAMA_URL`, `OLLAMA_MODEL`, `OLLAMA_TIMEOUT` e `WORKER_POLL` (segundos entre consultas, padrão 10). Ele não pega tarefas enquanto o Ollama não responde, e uma tarefa reservada que não voltar em 5 minutos volta para a fila. `./install.sh --worker --uninstall` remove o serviço.

### 3. Firebase Hosting (frontend)

1. Crie o projeto `contaslt` no [console do Firebase](https://console.firebase.google.com) (ou `firebase projects:create contaslt`); se o id já estiver tomado, troque em `frontend/.firebaserc` e no workflow.
2. Coloque a URL do Railway em `frontend/.env.production` (`VITE_API_URL`, sem barra no fim) e os domínios do Hosting em `CORS_ORIGINS` no Railway.
3. Primeiro deploy à mão: `cd frontend && firebase login && firebase deploy --only hosting` (o `predeploy` do `firebase.json` roda o build).
4. Deploy automático: `firebase init hosting:github` cria a service account e grava o secret no repositório; o workflow `.github/workflows/firebase-hosting.yml` já espera o nome `FIREBASE_SERVICE_ACCOUNT_CONTASLT` e publica a cada push na `main` que mexa em `frontend/`. Pode descartar os workflows que o `init` gerar.

O `firebase.json` devolve `index.html` para as rotas do Vue Router e põe cache longo nos assets com hash e `no-cache` em `index.html`, `sw.js` e no manifesto, para a PWA atualizar sozinha.

### O que muda em relação ao uso local

- Comprovantes: no Railway o disco não sobrevive ao deploy, por isso o Supabase Storage. Sem `SUPABASE_URL` o backend continua gravando em `STORAGE_DIR` (uso local ou um *volume* do Railway montado nessa pasta).
- Leitura de comprovantes: não há Ollama no servidor. O padrão em produção é a fila com o worker do seu computador (acima). `RECEIPT_PROVIDER=openai` usa qualquer API de chat compatível com OpenAI que aceite imagem e `response_format` com JSON Schema; `none` deixa só o botão respondendo "não disponível".
- CI: `.github/workflows/ci.yml` roda `mvnw test` (H2) e `npm run build` em cada push/PR.

## Como funciona

**Contas recorrentes** são regras (valor, frequência, primeiro vencimento, fim opcional ou número de parcelas). Os vencimentos de cada mês são materializados como lançamentos sob demanda, na primeira vez que o mês é consultado, de forma idempotente. Editar a regra atualiza as ocorrências ainda não pagas; excluir remove as pendentes e preserva as pagas no histórico. Um lançamento existente (por exemplo, importado do extrato) pode virar conta recorrente pelo botão "Tornar recorrente" no próprio lançamento: ele passa a ser a primeira ocorrência da regra (`fromTransactionId` na criação), sem duplicar, e os próximos vencimentos são gerados a partir da data dele. Cada ocorrência guarda a data prevista pela regra (`scheduled_date`) separada da data real, então mover um vencimento não faz o gerador recriar o original. Ao editar uma ocorrência há dois alcances (`PUT /api/transactions/{id}?scope=THIS|FOLLOWING`): só ela, ou ela e os próximos vencimentos pendentes. No segundo caso, valor, descrição, conta e categoria passam para a regra e para as próximas ocorrências; se a data mudar, a regra é reancorada nesse vencimento (`start_number` preserva a numeração das parcelas) e os próximos passam a seguir o novo dia. Ocorrências anteriores não mudam.

Um vencimento pendente é uma **previsão** (data e valor estimados pela regra); o pagamento real chega pelo extrato ou pelo comprovante. Na prévia da importação e na leitura de comprovante, as linhas são casadas com os vencimentos pendentes das contas recorrentes ativas (`OccurrenceMatcher`): a chave forte é o **nome** (alguma palavra significativa em comum com o nome da regra ou com as descrições dos pagamentos anteriores dela, ignorando termos bancários genéricos), e data e valor podem variar dentro da **precisão** configurada em cada regra (`matchStrictness`, 1 a 10: 1 = só o nome dentro do mês, 5 = ±30% e 15 dias, 10 = valor e data exatos). Ao vincular, a ocorrência vira o pagamento real: recebe valor, data e identificador do extrato e fica paga, mantendo a data prevista para comparação ("previsto 01/11 · estimado R$ 99,99" na lista). Os meses do período importado têm seus vencimentos gerados antes do casamento, para que não falte candidato.

**Relatórios:** "Gastos por categoria" expande o painel da tela inicial: as despesas do mês (todas as contas menos cartões, pagas e pendentes) por categoria de primeiro nível, com participação, variação em relação ao mês anterior, as subcategorias ("Geral" é o que foi lançado direto na categoria) e os últimos 6 meses de cada uma; cada linha leva aos lançamentos filtrados. "Gasto por itens" agrupa os itens lidos dos comprovantes pelo nome normalizado (sem acentos, dígitos e pontuação, então "Leite Integral 1L" e "LEITE INTEGRAL 1l" são o mesmo item), mostrando a categoria da compra mais recente, quantas vezes e em quais meses foi comprado, o gasto por mês e a evolução do preço unitário (informado no cupom ou total ÷ quantidade), com filtros de período, categoria e nome. Compras no cartão entram aqui: o que importa é o produto, não o caixa.

**Resumo do mês** na lista de lançamentos, como o extrato da conta: Saldo anterior (saldo inicial + tudo pago antes do período), Transferência (líquido do que entrou e saiu por transferência), Receitas, Despesas e Saldo atual = anterior + transferência + receitas − despesas, o número que deve bater com o banco. Segue só o recorte de contas (a filtrada, ou todas as ativas menos cartões) e o mês; ignora os outros filtros e conta só o que está pago. O que ainda está pendente no período aparece só como "previsto" sob cada valor (transferência, receitas e despesas pagas mais as pendentes) e sob o saldo (saldo atual mais todos os pendentes). O mesmo saldo acumulado aparece no cabeçalho de cada dia da lista.

**Cartão de crédito** é uma conta do tipo cartão, com dia de fechamento e de vencimento. Cada compra no crédito é um lançamento nessa conta, na data da compra, com itens e comprovante (é o que alimenta o histórico de preços), mas compras de cartão **não aparecem** na lista geral nem entram em receitas/despesas, caixa ou painel: só na visão do próprio cartão (`accountId=<cartão>`, com `invoice=true` para ver por fatura). A **fatura** é o recorte das compras entre dois fechamentos, identificada pelo mês do vencimento (`InvoicePeriod`; `GET /api/accounts/{id}/invoices?month=`). O que conta como despesa é a **fatura paga**: uma despesa comum na conta que pagou, ligada ao cartão e ao mês (`invoiceCardId` + `invoiceMonth` no `TransactionRequest`; colunas `card_account_id`/`card_invoice_month`). Essa despesa agrega as compras do período (o DTO traz `invoice` com contagem, total registrado, pago e "não detalhado" = pago além das compras registradas, ou seja, compras sem comprovante lido). Ela chega pela importação do extrato (linhas com "fatura"/"pagamento cartão" ou o nome de um cartão são oferecidas como "Pagar fatura", com cartão e mês) ou é criada à mão no formulário ("Pagamento de fatura de cartão", que lista as compras agregadas). O saldo de um cartão é a dívida em aberto: compras cuja fatura ainda não teve pagamento registrado. No extrato do próprio cartão, "pagamento recebido" é sinalizado para não ser importado. Comprovante lido com forma de pagamento "crédito" pré-seleciona o cartão. Cartão não entra em transferências.

**Subcategorias**: cada categoria pode ter subcategorias (um nível só, ex.: Contas da casa › Energia). A subcategoria herda o tipo do pai. Os seletores de categoria são em cascata (categoria, depois subcategoria). Filtrar lançamentos pela categoria pai inclui as subcategorias, e o painel "Despesas por categoria" soma as subcategorias no pai. Arquivar o pai arquiva as filhas; o pai não pode ser excluído enquanto tiver subcategorias.

**Categorização na importação** usa duas fontes, nesta ordem: nome de categoria vindo da planilha e regras "palavra-chave → categoria" do usuário. Não adivinha pelo histórico de lançamentos: sem regra, a linha fica sem categoria. Ao confirmar uma importação, as categorias escolhidas viram regras automaticamente. Duplicados são detectados por `FITID` (OFX) ou por data + valor + descrição.

**Transferências entre contas** são pares de lançamentos vinculados: a saída (despesa) na conta de origem e a entrada (receita) na de destino. Cada perna continua valendo para o saldo da própria conta, mas o par é ignorado em receitas, despesas, categorias e histórico, porque é só movimentação interna (ex.: PJ → PF). O cruzamento automático sugere pares com mesmo valor, contas diferentes e até 3 dias de distância, priorizando datas próximas e descrições do tipo "transferência/Pix/TED"; o usuário confirma, e pode também vincular manualmente, criar uma transferência direto no formulário ou desvincular. Excluir uma perna desfaz o vínculo e mantém a outra como lançamento comum.

**Comprovantes (foto de cupom fiscal / NFC-e):** no formulário de despesa, "Ler comprovante" envia a foto para `POST /api/receipts/scan`. O backend reduz a imagem (JPEG, lado maior 1600px), guarda em `app.storage.dir/receipts/` e pede a um modelo de visão local via Ollama (`app.ollama.url`, `app.ollama.model`, padrão `qwen3.5:9b-q8_0`) um JSON com esquema fixo: estabelecimento, data, total, forma de pagamento, chave de acesso e itens (descrição, quantidade, unitário, total). O formulário é preenchido com isso, os itens ficam editáveis e são gravados junto com o lançamento (`transaction_items`). A chave de acesso evita lançar o mesmo cupom duas vezes.

**Vínculo com o extrato:** ao importar um OFX/Excel, a prévia procura lançamentos manuais (não vindos de importação, transferência ou recorrência) com o mesmo valor e tipo até 3 dias de distância; a linha mostra "Você já lançou X" com a escolha *Vincular* (padrão) ou *Criar novo*. Vincular faz o lançamento existente, com itens, comprovante e categoria, representar a linha do extrato (recebe o `FITID` e entra no lote), sem duplicar. Excluir o lote apaga só os lançamentos criados por ele; os vinculados apenas perdem o vínculo.

**Excel:** a primeira planilha é lida; a linha de cabeçalho é detectada por nomes como *Data*, *Descrição/Histórico*, *Valor* (opcionais: *Tipo*, *Categoria*). Sem cabeçalho, assume-se a ordem data | descrição | valor. Valores negativos ou coluna *Tipo* = débito viram despesas.

## API (resumo)

Todas as rotas exceto `/api/auth/*` exigem `Authorization: Bearer <token>`. Erros seguem RFC 9457 (`application/problem+json`), com `errors` por campo em validações.

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | Cadastro e login (retornam token + usuário) |
| GET | `/api/auth/me` | Usuário atual |
| GET/POST/PUT/DELETE | `/api/accounts` | Contas (saldo = inicial + lançamentos pagos, inclusive pernas de transferência) |
| GET/POST/PUT/DELETE | `/api/categories` | Categorias (cor + ícone; `parentId` para subcategoria) |
| GET | `/api/transactions?month=AAAA-MM&accountId&categoryId&type&paid&search&transfers` | Lançamentos do mês + resumo (`transfers=true` só transferências, `false` sem elas) |
| POST/PUT/DELETE | `/api/transactions/{id}` | CRUD de lançamentos (aceita `items`, `merchant`, `receiptFile`, `receiptAccessKey`) |
| PATCH | `/api/transactions/{id}/paid` | Marcar pago/pendente |
| GET/POST/PUT/DELETE | `/api/recurring-bills` | Contas recorrentes |
| POST | `/api/imports/preview` (multipart `file`, `accountId`) | Lê o arquivo e sugere categorias |
| POST | `/api/imports/confirm` | Cria os lançamentos selecionados |
| GET | `/api/imports` | Histórico de importações |
| DELETE | `/api/imports/{id}` | Exclui a importação e todos os lançamentos gerados por ela |
| POST | `/api/transfers` | Cria uma transferência entre contas (gera as duas pernas já vinculadas) |
| POST | `/api/transfers/link` | Vincula uma despesa e uma receita existentes como transferência |
| GET | `/api/transfers/suggestions` | Pares candidatos: mesmo valor, contas diferentes, até 3 dias de diferença |
| DELETE | `/api/transfers/{id}` | Desvincula; os dois lançamentos continuam como despesa/receita comuns |
| POST | `/api/receipts/scan` (multipart `file`) | Lê a foto do comprovante com o modelo de visão e devolve itens, total, data e sugestões; com o provedor `worker`, devolve `status=PENDING` e `jobId` |
| GET | `/api/receipts/jobs/{id}` | Estado da leitura na fila; pronta, a mesma resposta do `scan` |
| POST | `/api/transactions/{id}/receipt-scan` | `{action: ACCEPT\|DISMISS\|RETRY}` para o aviso de leitura adiada (usar o lido, manter o lançado, ler de novo) |
| POST/GET | `/api/worker/ping`, `/api/worker/jobs/claim`, `/api/worker/jobs/{id}/image`, `/api/worker/jobs/{id}/result`, `/api/worker/jobs/{id}/fail` | Rotas do worker (header `X-Worker-Token`, sem JWT) |
| GET | `/api/receipts/{file}` | Imagem do comprovante (só do próprio usuário) |
| GET/POST/DELETE | `/api/category-rules` | Regras de categorização |
| GET | `/api/dashboard?month=AAAA-MM` | Totais, categorias, vencidos, próximos 7 dias, 6 meses |
| GET | `/api/reports/categories?month=AAAA-MM` | Despesas do mês por categoria, com subcategorias, mês anterior e 6 meses de histórico |
| GET | `/api/reports/items?from=AAAA-MM&to=AAAA-MM&categoryId&search` | Itens de comprovante agrupados por nome: categoria, compras, total por mês e evolução do preço unitário (padrão: últimos 12 meses) |

## Estrutura

```
backend/   Spring Boot (br.com.contaslt: auth, account, category, transaction, recurring, imports, dashboard)
frontend/  Vue (src/pages, src/components/app, src/components/ui (shadcn), src/stores, src/api)
docker-compose.yml  PostgreSQL 17
```
