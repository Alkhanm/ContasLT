# ContasLT

Controle financeiro pessoal no estilo Organizze, com foco em **contas recorrentes**, **categorização** e **importação de extratos (OFX e Excel)**. PWA instalável, tema claro/escuro com tendência ao verde escuro.

**Local-first:** todos os dados ficam no aparelho e todas as regras de negócio rodam no app (funciona offline e abre na hora). O backend só faz login, guarda e sincroniza os dados e lê comprovantes (foto, QR code e modelo de visão). O mesmo protocolo de sincronização serve para o futuro app Android nativo. Ver [Arquitetura local-first](#arquitetura-local-first).

- **Frontend:** Vue 3 + TypeScript + Vite + Tailwind v4 + shadcn-vue (reka-ui) + Pinia + vite-plugin-pwa + Dexie (IndexedDB)
- **Backend:** Spring Boot 4.1 (Java 21) + Spring Security (JWT HS256) + Spring Data JPA + Flyway + PostgreSQL
- **Importação (no app):** parser OFX próprio (SGML/XML, moeda pt-BR) e SheetJS para `.xlsx/.xls`

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
cd backend && ./mvnw test        # sync, migração dos dados, comprovantes/worker (H2 em memória)
cd frontend && npm test          # regras de negócio, dados locais e sincronização (Vitest, IndexedDB falso)
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
| `JWT_EXPIRATION` | `P30D` | Validade do token (ISO-8601 Duration). Só a sincronização precisa dele: o app abre offline com os dados do aparelho |
| `CORS_ORIGINS` | `http://localhost:[*]`, redes locais (`192.168.*`, `10.*`, `172.*`) e Tailscale (`100.*`), qualquer porta | Origens permitidas (padrões com `*` no host e `[*]` na porta). **Obrigatória no profile `prod`** |
| `PORT` | `8080` | Porta HTTP (o Railway injeta a dele) |
| `TOMCAT_THREADS` | `16` | Threads de requisição |
| `SPA_DIR` | vazio | Pasta com o build do frontend (`frontend/dist`); quando informada, o backend serve o frontend e devolve `index.html` para as rotas da aplicação |
| `RECEIPT_PROVIDER` | `ollama` (`worker` no profile `prod`) | Quem lê os comprovantes: `ollama` (local), `worker` (fila lida pelo computador do usuário, ver abaixo), `openai` (API compatível com OpenAI) ou `none` (desligado) |
| `WORKER_TOKEN` | vazio | Segredo do worker (`X-Worker-Token`) para as rotas `/api/worker/**`; vazio = rotas fechadas |
| `PREMIUM_ENFORCE` | `true` | Recursos premium (leitura de comprovantes e backup) só para usuários com `premium = TRUE` na tabela `users`; 403 para os demais, e os botões somem. `false` libera para todos, por exemplo no uso local |
| `OLLAMA_URL` | `http://localhost:11434` | Servidor Ollama usado para ler comprovantes |
| `OLLAMA_MODEL` | `qwen3.5:9b-q8_0` | Modelo de visão (precisa da capacidade *vision*) |
| `OLLAMA_TIMEOUT` | `PT3M` | Tempo máximo de uma leitura |
| `OPENAI_URL` | `https://api.openai.com/v1` | Base da API compatível com OpenAI. Gemini: `https://generativelanguage.googleapis.com/v1beta/openai`; Groq: `https://api.groq.com/openai/v1`; OpenRouter: `https://openrouter.ai/api/v1` |
| `OPENAI_API_KEY` | vazio | Chave da API (obrigatória com `RECEIPT_PROVIDER=openai`) |
| `OPENAI_MODEL` | `gpt-4.1-mini` | Modelo com visão e *structured outputs* (ex.: `gemini-2.5-flash` no Gemini) |
| `OPENAI_TIMEOUT` | `PT2M` | Tempo máximo de uma leitura |
| `STORAGE_DIR` | `./data` | Pasta das imagens de comprovantes (`receipts/`), quando não há Supabase |
| `SUPABASE_URL` | vazio | URL do projeto Supabase (`https://<ref>.supabase.co`, a "Project URL" em Settings › API; **não** a URL do endpoint S3). Definida, os comprovantes vão para um bucket privado do Supabase Storage em vez do disco |
| `SUPABASE_SERVICE_KEY` | vazio | Chave secreta do projeto: `service_role` (legada, JWT) ou `sb_secret_...` (nova). Nunca a `anon`/`publishable`: o bucket é privado, sem RLS, e só a chave secreta passa |
| `SUPABASE_BUCKET` | `receipts` | Nome do bucket (criado na primeira gravação, se não existir) |
| `SPRING_PROFILES_ACTIVE` | vazio | `prod` em produção: exige `JWT_SECRET` e `CORS_ORIGINS`, desliga o Ollama por padrão e esconde mensagens internas de erro |

No frontend, `VITE_API_URL` define a base da API (vazio = mesma origem). O build de produção lê `frontend/.env.production` (URL do Railway, usada pelo Firebase); `prod.sh`/`install.sh` sobrescrevem com `VITE_API_URL=` para o build local falar com o próprio backend.

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
- **Computador desligado:** o app avisa, você salva o lançamento normalmente com a foto anexada. Quando o PC ligar, o worker lê a foto; na próxima sincronização o app busca a leitura (`POST /api/receipts/attach`) e os itens, o estabelecimento e a chave do cupom entram sozinhos no lançamento (sem sobrescrever o que você digitou). O servidor não grava lançamentos: só entrega a leitura, uma vez. Se o total ou a data do cupom não baterem com o lançado, o lançamento mostra um aviso com "Usar o do comprovante" / "Manter o lançado". Se a leitura falhar três vezes, aparece "Tentar de novo".

Instalação, no PC com o Ollama:

```bash
openssl rand -base64 32          # gere o segredo e cadastre como WORKER_TOKEN no Railway
printf 'CONTASLT_URL=https://contaslt-production.up.railway.app\nWORKER_TOKEN=<o mesmo segredo>\n' >> .env
./install.sh --worker            # serviço de usuário contaslt-worker, sobe no boot
journalctl --user -u contaslt-worker -f
```

**Quem pode usar:** a leitura consome a GPU do seu computador, então ela é só para contas marcadas como premium, e isso se faz direto no banco, sem tela:

```sql
UPDATE users SET premium = TRUE WHERE email = 'voce@exemplo.com';
```

Quem não tem a marca recebe 403 em `POST /api/receipts/scan` e no "tentar de novo", e o botão "Ler comprovante" nem aparece (a marca viaja no token de login e o app a guarda em cache, então depois de mudar no banco é sair e entrar de novo). No uso local, `PREMIUM_ENFORCE=false` no `.env` libera para todos.

### Exportar e importar os dados (premium)

Em **Mais › Exportar e importar** (feito no aparelho, com os dados locais; as fotos são baixadas e enviadas pelo `/api/receipts`): "Exportar dados" gera um `.zip` com `contaslt.json` e a pasta `receipts/` (fotos dos comprovantes). Só vai o que é do usuário logado: contas, categorias, regras, recorrentes e lançamentos com itens e transferências. Não é um dump do banco: o arquivo não tem ids; conta e categoria aparecem pelo nome ("Pai › Filha"), e o par de uma transferência e a regra recorrente de uma ocorrência são posições dentro do próprio arquivo.

"Importar dados" **soma** ao que a conta já tem: contas e categorias são casadas pelo nome (e criadas se faltarem), regras pela palavra-chave, recorrentes por descrição + tipo + frequência + data inicial + valor, e um lançamento é pulado se já existir um igual (mesma conta, data, tipo, valor e descrição, ou o mesmo identificador de extrato na conta, ou a mesma ocorrência da recorrente). Importar o mesmo arquivo duas vezes não duplica nada; importar depois de apagar algo por engano repõe só o que faltava. Transferências só são religadas quando as duas pernas entram juntas. Fotos entram com nome novo, dado pelo servidor. O formato do arquivo é o mesmo da versão em que o backup rodava no servidor, então arquivos antigos continuam entrando.

### 3. Firebase Hosting (frontend)

1. Crie o projeto `contaslt` no [console do Firebase](https://console.firebase.google.com) (ou `firebase projects:create contaslt`); se o id já estiver tomado, troque em `frontend/.firebaserc` e no workflow.
2. Coloque a URL do Railway em `frontend/.env.production` (`VITE_API_URL`, sem barra no fim) e os domínios do Hosting em `CORS_ORIGINS` no Railway.
3. Primeiro deploy à mão: `cd frontend && firebase login && firebase deploy --only hosting` (o `predeploy` do `firebase.json` roda o build).
4. Deploy automático: `firebase init hosting:github` cria a service account e grava o secret no repositório; o workflow `.github/workflows/firebase-hosting.yml` já espera o nome `FIREBASE_SERVICE_ACCOUNT_CONTASLT` e publica a cada push na `main` que mexa em `frontend/`. Pode descartar os workflows que o `init` gerar.

O `firebase.json` devolve `index.html` para as rotas do Vue Router e põe cache longo nos assets com hash e `no-cache` em `index.html`, `sw.js` e no manifesto, para a PWA atualizar sozinha.

### O que muda em relação ao uso local

- Comprovantes: no Railway o disco não sobrevive ao deploy, por isso o Supabase Storage. Sem `SUPABASE_URL` o backend continua gravando em `STORAGE_DIR` (uso local ou um *volume* do Railway montado nessa pasta).
- Leitura de comprovantes: não há Ollama no servidor. O padrão em produção é a fila com o worker do seu computador (acima). `RECEIPT_PROVIDER=openai` usa qualquer API de chat compatível com OpenAI que aceite imagem e `response_format` com JSON Schema; `none` deixa só o botão respondendo "não disponível".
- Dados no aparelho: o app lê e calcula tudo localmente; o servidor só é usado para sincronizar (ao abrir, ao voltar para o app, ao reconectar, logo depois de gravar e a cada 3 minutos), para o login e para comprovantes. A latência Railway↔Supabase deixa de pesar nas telas.
- CI: `.github/workflows/ci.yml` roda `mvnw test` (H2), `npm test` (Vitest) e `npm run build` em cada push/PR.

## App Android (`android/`)

App nativo em **Java 21** (Views + Material 3, sem Kotlin), com o mesmo visual do PWA (cores, Manrope, ícones Lucide convertidos) e as mesmas regras: o domínio e os serviços de `frontend/src/domain` e `frontend/src/services` foram portados 1:1 para `android/app/src/main/java/br/com/contaslt/{domain,services}`, com os mesmos testes (JUnit). Os dados ficam no aparelho (Room, um banco por usuário) e sincronizam pelo mesmo `/api/sync`, no mesmo formato de registro (`frontend/src/data/schema.ts`), então PWA e Android veem os mesmos dados, e o id de um vencimento recorrente é o mesmo nos dois.

**Abrir e rodar:** Android Studio › *Open* › pasta `android/` (JDK 21, SDK 37). Ou pela linha de comando:

```bash
cd android
./gradlew test            # domínio, serviços, sincronização, leitura de notificações (JVM)
./gradlew assembleDebug   # APK em app/build/outputs/apk/debug/
```

O servidor padrão é o do Railway (`DEFAULT_SERVER` em `app/build.gradle.kts`). Nos builds `debug` e `staging`, toque em **Servidor** na tela de login para usar o backend local: `http://10.0.2.2:8080` no emulador, o IP da máquina na rede/Tailscale num aparelho, ou `adb reverse tcp:8080 tcp:8080` e `http://localhost:8080`. No build da loja (`release`) o campo não aparece e só HTTPS é aceito.

| Build | Para quê | R8 | HTTP sem TLS | Assinatura |
|---|---|---|---|---|
| `debug` | desenvolvimento | não | sim | debug |
| `staging` | testar o build otimizado contra o backend local (id `br.com.contaslt.staging`, instala ao lado) | sim | sim | debug |
| `release` | Play Store | sim | não | chave de upload (`keystore.properties`) |

**Lançamentos pelas notificações dos bancos:** em *Mais › Notificações dos bancos*, libere o "Acesso a notificações" para o ContasLT (`BankNotificationListener`). O app lê só as notificações dos apps marcados (os bancos conhecidos instalados já vêm marcados; outros podem ser adicionados), no aparelho, sem enviar nada para fora. Cada compra, Pix ou pagamento reconhecido (`notify/BankParser`) vira uma sugestão na caixa **A confirmar**, já com conta (pelo final do cartão ou a conta/cartão padrão escolhidos para aquele app), categoria pelas regras de palavra-chave, e o vencimento recorrente pendente que ele parece pagar. Confirmar cria o lançamento (ou paga o vencimento), Editar abre o formulário preenchido, Descartar some com ela; uma notificação própria do ContasLT traz os botões Confirmar e Descartar. Textos que não viram movimento (códigos, fatura fechada, compra recusada, propaganda) são ignorados; os de apps monitorados que não forem reconhecidos ficam em "Não reconhecidas". A mesma tela tem um **Testar** para colar o texto de uma notificação e ver como ele é lido. Para testar no emulador:

```bash
adb shell cmd notification allow_listener br.com.contaslt/br.com.contaslt.notify.BankNotificationListener
adb shell "cmd notification post -S bigtext -t 'Compra aprovada' t1 'Compra de R\$ 45,90 APROVADA em PADARIA X para o cartão com final 1234'"
```

(as aspas simples por dentro são necessárias: o `adb shell` junta e reparte os argumentos. O pacote que posta pelo `adb` é `com.android.shell`: adicione-o em *Adicionar* na lista de apps lidos, digitando o nome do pacote.) A caixa fica só no aparelho, não sincroniza; o lançamento confirmado sincroniza normalmente.

### Publicar na Play Store

**1. Chave de upload** (uma vez; guarde o arquivo e as senhas fora do repositório: sem eles, só o suporte do Google troca a chave):

```bash
keytool -genkeypair -v -keystore ~/contaslt-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Seu Nome, O=Sua Empresa, L=Cidade, ST=Estado, C=BR"
```

e crie `android/keystore.properties` (está no `.gitignore`):

```
storeFile=/home/<você>/contaslt-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Sem esse arquivo o `release` é assinado com a chave de debug, que a Play Store recusa. Na Play Console, ative a *Assinatura de apps pelo Google Play* (a chave acima é só a de upload).

**2. Gerar o pacote** (cada envio precisa de um `versionCode` maior):

```bash
cd android
./gradlew bundleRelease -PversionCode=1 -PversionName=1.0    # app/build/outputs/bundle/release/app-release.aab
./gradlew assembleStaging                                     # mesmo build otimizado, para testar antes
```

**3. Antes de enviar a primeira versão:** o backend precisa estar com a rota de exclusão de conta (`POST /api/auth/delete-account`) no ar, e o PWA com a página `/excluir-conta`.

**4. Play Console:**
- *Política de privacidade*: `https://contaslt.web.app/privacidade` (`frontend/src/pages/PrivacyPage.vue`; preencha o e-mail de contato antes de publicar).
- *Exclusão de conta*: no app em **Mais › Excluir minha conta**; na web, `https://contaslt.web.app/excluir-conta`. Apaga a conta, os dados sincronizados e as fotos dos comprovantes do servidor.
- *Segurança dos dados*: coletados e enviados ao servidor (para sincronizar): nome, e-mail, lançamentos, contas, categorias, fotos de comprovantes; criptografados em trânsito; o usuário pode pedir a exclusão. Lidos só no aparelho, sem envio: o conteúdo das notificações dos apps de banco escolhidos.
- *Recursos financeiros*: app de controle de finanças pessoais, sem serviço financeiro (sem empréstimo, pagamento ou investimento).
- *Ficha*: ícone 512 px e gráfico de destaque 1024×500 em `android/play/`; capturas de tela do celular; categoria Finanças.
- *Acesso a notificações*: o app mostra um aviso de consentimento (`ui/inbox/NotificationDisclosure`) antes de abrir as configurações do sistema; descreva o mesmo uso na ficha e na política de privacidade.
- Conta de desenvolvedor pessoal criada recentemente: o Google exige um teste fechado com pelo menos 12 testadores por 14 dias antes de liberar a produção.

O app consulta só os pacotes dos bancos conhecidos (`<queries>` no `AndroidManifest.xml`, igual a `notify/BankApps.java`); para outro app, o usuário digita o nome do pacote. Não há visibilidade ampla de pacotes nem cobrança dentro do app (se um dia houver, precisa ser pelo faturamento do Google Play).

## Arquitetura local-first

Os apps (PWA e Android nativo) são donos dos dados e das regras; o servidor é um armazenamento sincronizado.

- **No aparelho** (`frontend/src/data`): um banco IndexedDB por usuário (Dexie) com todos os registros, espelhado em memória (`LocalStore`). Gravar atualiza a memória na hora e grava registro + `outbox` numa transação. As telas continuam chamando `src/api/index.ts` (mesma superfície de antes), que agora executa os serviços locais (`src/services`, o antigo backend portado) sobre o domínio puro (`src/domain`: datas como o java.time, recorrência, casamento com extrato, fatura, OFX/Excel, UUID v5).
- **Formato dos registros** (`frontend/src/data/schema.ts`): o contrato entre os clientes. Ids UUID gerados no aparelho, dinheiro em centavos (inteiro), datas `AAAA-MM-DD`. O servidor não lê os campos.
- **Sincronização** (`frontend/src/data/sync.ts`, `backend/.../sync`): `POST /api/sync/push` sobe o outbox em lotes; `GET /api/sync/pull?since=<cursor>` desce o que mudou acima da maior versão já vista. Conflito: vence a gravação com o `updatedAt` mais novo (relógio do aparelho), por registro; o push devolve, na versão do servidor, o que ele recusou. Exclusão é marcação (`deleted`), para chegar aos outros aparelhos. Entre abas, quem grava avisa pelo `BroadcastChannel` e só uma aba sincroniza por vez (Web Locks).
- **Vencimentos de recorrentes** são gerados no aparelho com id determinístico (UUID v5 de regra + data prevista), então dois aparelhos offline geram o mesmo registro. O gerado vai com relógio mínimo: qualquer edição real (ex.: pagar) vence. O app só gera depois da primeira sincronização completa do aparelho (antes, mostra "Baixando seus dados").
- **Primeiro acesso / cadastro:** o cadastro cria só o usuário; o app cria as categorias padrão e a "Conta principal". Sair apaga os dados do aparelho, a não ser que algo ainda não tenha subido (aí ficam guardados para o próximo login do mesmo usuário). Sessão expirada não apaga nada.
- **Migração:** a V13 converte as tabelas relacionais antigas em `sync_records` com UUIDs determinísticos (`tabela:id`), mantendo as referências; as tabelas antigas ficam no banco, sem uso.

## Como funciona

As regras abaixo rodam no app (`frontend/src/services` e `frontend/src/domain`). Nomes de classes e rotas citados vêm da época em que elas ficavam no backend; os equivalentes no app: `RecurringBillService` → `services/recurring.ts`, `OccurrenceMatcher` → `domain/matcher.ts`, `InvoicePeriod`/`CardInvoiceService` → `domain/invoice.ts`/`services/cards.ts`, `TransactionService` (e o `PUT /api/transactions/{id}?scope=`) → `services/transactions.ts`, `ImportService` → `services/imports.ts`, `TransferService` → `services/transfers.ts`, `ReportService` → `services/reports.ts`.

**Contas recorrentes** são regras (valor, frequência, primeiro vencimento, fim opcional ou número de parcelas). Os vencimentos de cada mês são materializados como lançamentos sob demanda, na primeira vez que o mês é consultado, de forma idempotente. Editar a regra atualiza as ocorrências ainda não pagas; excluir remove as pendentes e preserva as pagas no histórico. Um lançamento existente (por exemplo, importado do extrato) pode virar conta recorrente pelo botão "Tornar recorrente" no próprio lançamento: ele passa a ser a primeira ocorrência da regra (`fromTransactionId` na criação), sem duplicar, e os próximos vencimentos são gerados a partir da data dele. Cada ocorrência guarda a data prevista pela regra (`scheduled_date`) separada da data real, então mover um vencimento não faz o gerador recriar o original. Ao editar uma ocorrência há dois alcances (`PUT /api/transactions/{id}?scope=THIS|FOLLOWING`): só ela, ou ela e os próximos vencimentos pendentes. No segundo caso, valor, descrição, conta e categoria passam para a regra e para as próximas ocorrências; se a data mudar, a regra é reancorada nesse vencimento (`start_number` preserva a numeração das parcelas) e os próximos passam a seguir o novo dia. Ocorrências anteriores não mudam.

Um vencimento pendente é uma **previsão** (data e valor estimados pela regra); o pagamento real chega pelo extrato ou pelo comprovante. Na prévia da importação e na leitura de comprovante, as linhas são casadas com os vencimentos pendentes das contas recorrentes ativas (`OccurrenceMatcher`): a chave forte é o **nome** (alguma palavra significativa em comum com o nome da regra ou com as descrições dos pagamentos anteriores dela, ignorando termos bancários genéricos), e data e valor podem variar dentro da **precisão** configurada em cada regra (`matchStrictness`, 1 a 10: 1 = só o nome dentro do mês, 5 = ±30% e 15 dias, 10 = valor e data exatos). Ao vincular, a ocorrência vira o pagamento real: recebe valor, data e identificador do extrato e fica paga, mantendo a data prevista para comparação ("previsto 01/11 · estimado R$ 99,99" na lista). Os meses do período importado têm seus vencimentos gerados antes do casamento, para que não falte candidato.

**Relatórios:** "Gastos por categoria" expande o painel da tela inicial: as despesas do mês (todas as contas menos cartões, pagas e pendentes) por categoria de primeiro nível, com participação, variação em relação ao mês anterior, as subcategorias ("Geral" é o que foi lançado direto na categoria) e os últimos 6 meses de cada uma; cada linha leva aos lançamentos filtrados. "Gasto por itens" agrupa os itens lidos dos comprovantes pelo nome normalizado (sem acentos, dígitos e pontuação, então "Leite Integral 1L" e "LEITE INTEGRAL 1l" são o mesmo item), mostrando a categoria da compra mais recente, quantas vezes e em quais meses foi comprado, o gasto por mês e a evolução do preço unitário (informado no cupom ou total ÷ quantidade), com filtros de período, categoria e nome. Compras no cartão entram aqui: o que importa é o produto, não o caixa.

**Resumo do mês** na lista de lançamentos, como o extrato da conta: Saldo anterior (saldo inicial + tudo pago antes do período), Transferência (líquido do que entrou e saiu por transferência), Receitas, Despesas e Saldo atual = anterior + transferência + receitas − despesas, o número que deve bater com o banco. Segue só o recorte de contas (a filtrada, ou todas as ativas menos cartões) e o mês; ignora os outros filtros e conta só o que está pago. O que ainda está pendente no período aparece só como "previsto" sob cada valor (transferência, receitas e despesas pagas mais as pendentes) e sob o saldo (saldo atual mais todos os pendentes). O mesmo saldo acumulado aparece no cabeçalho de cada dia da lista.

**Cartão de crédito** é uma conta do tipo cartão, com dia de fechamento e de vencimento. Cada compra no crédito é um lançamento nessa conta, na data da compra, com itens e comprovante (é o que alimenta o histórico de preços), mas compras de cartão **não aparecem** na lista geral nem entram em receitas/despesas, caixa ou painel: só na visão do próprio cartão (`accountId=<cartão>`, com `invoice=true` para ver por fatura). A **fatura** é o recorte das compras entre dois fechamentos, identificada pelo mês do vencimento (`InvoicePeriod`; `GET /api/accounts/{id}/invoices?month=`). O que conta como despesa é a **fatura paga**: uma despesa comum na conta que pagou, ligada ao cartão e ao mês (`invoiceCardId` + `invoiceMonth` no `TransactionRequest`; colunas `card_account_id`/`card_invoice_month`). Essa despesa agrega as compras do período (o DTO traz `invoice` com contagem, total registrado, pago e "não detalhado" = pago além das compras registradas, ou seja, compras sem comprovante lido). Ela chega pela importação do extrato (linhas com "fatura"/"pagamento cartão" ou o nome de um cartão são oferecidas como "Pagar fatura", com cartão e mês) ou é criada à mão no formulário ("Pagamento de fatura de cartão", que lista as compras agregadas). O saldo de um cartão é a dívida em aberto: compras cuja fatura ainda não teve pagamento registrado. No extrato do próprio cartão, "pagamento recebido" é sinalizado para não ser importado. Comprovante lido com forma de pagamento "crédito" pré-seleciona o cartão. Cartão não entra em transferências.

**Subcategorias**: cada categoria pode ter subcategorias (um nível só, ex.: Contas da casa › Energia). A subcategoria herda o tipo do pai. Os seletores de categoria são em cascata (categoria, depois subcategoria). Filtrar lançamentos pela categoria pai inclui as subcategorias, e o painel "Despesas por categoria" soma as subcategorias no pai. Arquivar o pai arquiva as filhas; o pai não pode ser excluído enquanto tiver subcategorias.

**Categorização na importação** usa duas fontes, nesta ordem: nome de categoria vindo da planilha e regras "palavra-chave → categoria" do usuário. Não adivinha pelo histórico de lançamentos: sem regra, a linha fica sem categoria. Ao confirmar uma importação, as categorias escolhidas viram regras automaticamente. Duplicados são detectados por `FITID` (OFX) ou por data + valor + descrição.

**Transferências entre contas** são pares de lançamentos vinculados: a saída (despesa) na conta de origem e a entrada (receita) na de destino. Cada perna continua valendo para o saldo da própria conta, mas o par é ignorado em receitas, despesas, categorias e histórico, porque é só movimentação interna (ex.: PJ → PF). O cruzamento automático sugere pares com mesmo valor, contas diferentes e até 3 dias de distância, priorizando datas próximas e descrições do tipo "transferência/Pix/TED"; o usuário confirma, e pode também vincular manualmente, criar uma transferência direto no formulário ou desvincular. "Não é transferência" tira o par das sugestões; quando os dois lançamentos são ocorrências de contas recorrentes (ex.: "Bitcoin" de R$ 500 no dia 1 e um aporte recorrente de R$ 500 no dia 1), uma recusa só vale para aquelas duas regras em todos os meses. As recusas podem ser esquecidas na própria folha de cruzamento. Excluir uma perna desfaz o vínculo e mantém a outra como lançamento comum.

**Comprovantes (foto de cupom fiscal / NFC-e):** no formulário de despesa, "Ler comprovante" envia a foto para `POST /api/receipts/scan`. O backend reduz a imagem (JPEG, lado maior 1600px), guarda em `app.storage.dir/receipts/` e pede a um modelo de visão local via Ollama (`app.ollama.url`, `app.ollama.model`, padrão `qwen3.5:9b-q8_0`) um JSON com esquema fixo: estabelecimento, data, total, forma de pagamento, chave de acesso e itens (descrição, quantidade, unitário, total). O formulário é preenchido com isso, os itens ficam editáveis e são gravados junto com o lançamento (`transaction_items`). A chave de acesso evita lançar o mesmo cupom duas vezes.

**Vínculo com o extrato:** ao importar um OFX/Excel, a prévia procura lançamentos manuais (não vindos de importação, transferência ou recorrência) com o mesmo valor e tipo até 3 dias de distância; a linha mostra "Você já lançou X" com a escolha *Vincular* (padrão) ou *Criar novo*. Vincular faz o lançamento existente, com itens, comprovante e categoria, representar a linha do extrato (recebe o `FITID` e entra no lote), sem duplicar. Excluir o lote apaga só os lançamentos criados por ele; os vinculados apenas perdem o vínculo.

**Excel:** a primeira planilha é lida; a linha de cabeçalho é detectada por nomes como *Data*, *Descrição/Histórico*, *Valor* (opcionais: *Tipo*, *Categoria*). Sem cabeçalho, assume-se a ordem data | descrição | valor. Valores negativos ou coluna *Tipo* = débito viram despesas.

## API (resumo)

Todas as rotas exceto `/api/auth/*` exigem `Authorization: Bearer <token>`. Erros seguem RFC 9457 (`application/problem+json`), com `errors` por campo em validações.

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | Cadastro e login (retornam token + usuário) |
| GET | `/api/auth/me` | Usuário atual |
| GET | `/api/sync/pull?since=<versão>&limit=1000` | Registros do usuário alterados acima da versão, em ordem (`records`, `cursor`, `hasMore`) |
| POST | `/api/sync/push` | `{changes: [{id, entity, data, deleted, updatedAt}]}` (até 500); devolve `accepted` e, em `rejected`, a versão do servidor do que ele já tinha mais novo |
| POST | `/api/receipts/scan` (multipart `file`) | Guarda a foto e devolve a leitura crua (`scan`) e a chave do QR (`qrAccessKey`); com o provedor `worker`, `status=PENDING` e `jobId` |
| GET | `/api/receipts/jobs/{id}` | Estado da leitura na fila; pronta, traz `scan` |
| POST | `/api/receipts/attach` | `{receiptFile, transactionId}`: a foto foi salva nesse lançamento do app; devolve o estado da leitura (e o resultado, uma vez) ou 204 |
| GET | `/api/receipts/results` | Leituras terminadas de fotos já salvas em lançamentos, ainda não entregues |
| POST | `/api/receipts/jobs/{id}/delivered`, `/api/receipts/retry` | Confirma a entrega / põe a foto de um lançamento na fila de novo (premium) |
| POST | `/api/receipts` (multipart `file`) | Guarda uma foto sem ler (importação de backup; premium) |
| GET | `/api/receipts/{file}` | Imagem do comprovante (só do próprio usuário) |
| POST/GET | `/api/worker/ping`, `/api/worker/jobs/claim`, `/api/worker/jobs/{id}/image`, `/api/worker/jobs/{id}/result`, `/api/worker/jobs/{id}/fail` | Rotas do worker (header `X-Worker-Token`, sem JWT) |

Contas, categorias, lançamentos, recorrentes, importação, transferências, faturas, painel, relatórios e backup não têm mais rotas: rodam no app, sobre os dados locais.

## Estrutura

```
backend/   Spring Boot (br.com.contaslt: auth, user, sync, receipt, worker; db.migration.V13 converte os dados antigos)
frontend/  Vue (src/pages, src/components, src/stores; src/api = superfície das telas; src/services = regras de negócio;
           src/domain = lógica pura; src/data = IndexedDB, memória e sincronização)
android/   Java (data = Room, memória e sincronização; domain e services = porte do frontend; notify = notificações
           dos bancos; ui = telas)
docker-compose.yml  PostgreSQL 17
```
