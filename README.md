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
| `DB_URL` | `jdbc:postgresql://localhost:5432/contaslt` | JDBC do PostgreSQL |
| `DB_USER` / `DB_PASSWORD` | `contaslt` / `contaslt` | Credenciais do banco |
| `JWT_SECRET` | chave de desenvolvimento | **Troque em produção** (mínimo 32 bytes) |
| `JWT_EXPIRATION` | `P7D` | Validade do token (ISO-8601 Duration) |
| `CORS_ORIGINS` | `http://localhost:[*]`, redes locais (`192.168.*`, `10.*`, `172.*`) e Tailscale (`100.*`), qualquer porta | Origens permitidas (padrões com `*` no host e `[*]` na porta) |
| `PORT` | `8080` | Porta HTTP |
| `SPA_DIR` | vazio | Pasta com o build do frontend (`frontend/dist`); quando informada, o backend serve o frontend e devolve `index.html` para as rotas da aplicação |
| `OLLAMA_URL` | `http://localhost:11434` | Servidor Ollama usado para ler comprovantes |
| `OLLAMA_MODEL` | `qwen3.5:9b-q8_0` | Modelo de visão (precisa da capacidade *vision*) |
| `OLLAMA_TIMEOUT` | `PT3M` | Tempo máximo de uma leitura |
| `STORAGE_DIR` | `./data` | Pasta das imagens de comprovantes (`receipts/`) |

No frontend, `VITE_API_URL` define a base da API (vazio = mesma origem).

## Como funciona

**Contas recorrentes** são regras (valor, frequência, primeiro vencimento, fim opcional ou número de parcelas). Os vencimentos de cada mês são materializados como lançamentos sob demanda, na primeira vez que o mês é consultado, de forma idempotente. Editar a regra atualiza as ocorrências ainda não pagas; excluir remove as pendentes e preserva as pagas no histórico. Um lançamento existente (por exemplo, importado do extrato) pode virar conta recorrente pelo botão "Tornar recorrente" no próprio lançamento: ele passa a ser a primeira ocorrência da regra (`fromTransactionId` na criação), sem duplicar, e os próximos vencimentos são gerados a partir da data dele. Cada ocorrência guarda a data prevista pela regra (`scheduled_date`) separada da data real, então mover um vencimento não faz o gerador recriar o original. Ao editar uma ocorrência há dois alcances (`PUT /api/transactions/{id}?scope=THIS|FOLLOWING`): só ela, ou ela e os próximos vencimentos pendentes. No segundo caso, valor, descrição, conta e categoria passam para a regra e para as próximas ocorrências; se a data mudar, a regra é reancorada nesse vencimento (`start_number` preserva a numeração das parcelas) e os próximos passam a seguir o novo dia. Ocorrências anteriores não mudam.

Um vencimento pendente é uma **previsão** (data e valor estimados pela regra); o pagamento real chega pelo extrato ou pelo comprovante. Na prévia da importação e na leitura de comprovante, as linhas são casadas com os vencimentos pendentes das contas recorrentes ativas (`OccurrenceMatcher`): a chave forte é o **nome** (alguma palavra significativa em comum com o nome da regra ou com as descrições dos pagamentos anteriores dela, ignorando termos bancários genéricos), e data e valor podem variar dentro da **precisão** configurada em cada regra (`matchStrictness`, 1 a 10: 1 = só o nome dentro do mês, 5 = ±30% e 15 dias, 10 = valor e data exatos). Ao vincular, a ocorrência vira o pagamento real: recebe valor, data e identificador do extrato e fica paga, mantendo a data prevista para comparação ("previsto 01/11 · estimado R$ 99,99" na lista). Os meses do período importado têm seus vencimentos gerados antes do casamento, para que não falte candidato.

**Resumo do mês** na lista de lançamentos: *Saldo anterior* é o resultado acumulado (receitas − despesas, pernas de transferência fora) de todos os meses antes do exibido, com os mesmos filtros da lista (conta, categoria, situação, busca); *Saldo atual* = saldo anterior + receitas − despesas do mês. É o resultado que passa de um mês para o outro, não o saldo bancário: o saldo das contas (saldo inicial + lançamentos pagos) aparece no cabeçalho de cada dia da lista e na página de contas.

**Cartão de crédito** é uma conta do tipo cartão, com dia de fechamento e de vencimento. Cada compra no crédito é uma despesa normal nessa conta, na data da compra (com itens e comprovante, se houver): é onde ela conta nas despesas e no histórico de preços. A **fatura** não é tabela, é o recorte das compras entre dois fechamentos, identificada pelo mês do vencimento (`InvoicePeriod`; `GET /api/transactions?accountId=<cartão>&invoice=true` lista por fatura e devolve o resumo em `invoice`; `GET /api/accounts/{id}/invoices?month=`). **Pagar a fatura** é uma transferência conta → cartão com `invoiceMonth` (inferido pelo vencimento mais próximo quando não informado). Como nem toda compra é registrada mas o pagamento sempre aparece no extrato, o que foi pago além das compras registradas vira uma despesa **"Compras não detalhadas"** no cartão (`transactions.invoice_gap`), datada no fechamento, recalculada a cada mudança (nova compra no período, pagamento vinculado ou desvinculado) e só editável em categoria e observações. Na importação do extrato da conta, linhas com "fatura"/"pagamento cartão" ou com o nome de um cartão são oferecidas como pagamento de fatura (`invoiceCardId`/`invoiceMonth` na confirmação); no extrato do próprio cartão, o "pagamento recebido" casa com a perna já registrada. Comprovante lido com forma de pagamento "crédito" pré-seleciona o cartão.

**Subcategorias**: cada categoria pode ter subcategorias (um nível só, ex.: Contas da casa › Energia). A subcategoria herda o tipo do pai. Os seletores de categoria são em cascata (categoria, depois subcategoria). Filtrar lançamentos pela categoria pai inclui as subcategorias, e o painel "Despesas por categoria" soma as subcategorias no pai. Arquivar o pai arquiva as filhas; o pai não pode ser excluído enquanto tiver subcategorias.

**Categorização na importação** usa duas fontes, nesta ordem: nome de categoria vindo da planilha e regras "palavra-chave → categoria" do usuário. Não adivinha pelo histórico de lançamentos: sem regra, a linha fica sem categoria. Ao confirmar uma importação, as categorias escolhidas viram regras automaticamente. Duplicados são detectados por `FITID` (OFX) ou por data + valor + descrição.

**Transferências entre contas** são pares de lançamentos vinculados: a saída (despesa) na conta de origem e a entrada (receita) na de destino. Cada perna continua valendo para o saldo da própria conta, mas o par é ignorado em receitas, despesas, categorias e histórico, porque é só movimentação interna (ex.: PJ → PF). Transferência com destino num cartão de crédito é pagamento de fatura. O cruzamento automático sugere pares com mesmo valor, contas diferentes e até 3 dias de distância, priorizando datas próximas e descrições do tipo "transferência/Pix/TED"; o usuário confirma, e pode também vincular manualmente, criar uma transferência direto no formulário ou desvincular. Excluir uma perna desfaz o vínculo e mantém a outra como lançamento comum.

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
| POST | `/api/receipts/scan` (multipart `file`) | Lê a foto do comprovante com o modelo de visão e devolve itens, total, data e sugestões |
| GET | `/api/receipts/{file}` | Imagem do comprovante (só do próprio usuário) |
| GET/POST/DELETE | `/api/category-rules` | Regras de categorização |
| GET | `/api/dashboard?month=AAAA-MM` | Totais, categorias, vencidos, próximos 7 dias, 6 meses |

## Estrutura

```
backend/   Spring Boot (br.com.contaslt: auth, account, category, transaction, recurring, imports, dashboard)
frontend/  Vue (src/pages, src/components/app, src/components/ui (shadcn), src/stores, src/api)
docker-compose.yml  PostgreSQL 17
```
