# ContasLT

Controle financeiro pessoal no estilo Organizze, com foco em **contas recorrentes**, **categorização** e **importação de extratos (OFX e Excel)**. PWA instalável, tema claro/escuro com tendência ao verde escuro.

- **Frontend:** Vue 3 + TypeScript + Vite + Tailwind v4 + shadcn-vue (reka-ui) + Pinia + vite-plugin-pwa
- **Backend:** Spring Boot 4.1 (Java 21) + Spring Security (JWT HS256) + Spring Data JPA + Flyway + PostgreSQL
- **Importação:** parser OFX próprio (SGML/XML, moeda pt-BR) e Apache POI para `.xlsx/.xls`

## Rodando em desenvolvimento

Pré-requisitos: Java 21, Node 20+, Docker.

```bash
# 1. Banco de dados
docker compose up -d db

# 2. Backend (porta 8080)
cd backend && ./mvnw spring-boot:run

# 3. Frontend (porta 5173, com proxy /api -> 8080)
cd frontend && npm install && npm run dev
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
| `CORS_ORIGINS` | `http://localhost:[*],http://127.0.0.1:[*],http://192.168.*.*:[*],...` | Origens permitidas (padrões com `*` no host e `[*]` na porta) |
| `PORT` | `8080` | Porta HTTP |

No frontend, `VITE_API_URL` define a base da API (vazio = mesma origem).

## Como funciona

**Contas recorrentes** são regras (valor, frequência, primeiro vencimento, fim opcional ou número de parcelas). Os vencimentos de cada mês são materializados como lançamentos sob demanda, na primeira vez que o mês é consultado, de forma idempotente. Editar a regra atualiza as ocorrências ainda não pagas; excluir remove as pendentes e preserva as pagas no histórico.

**Categorização na importação** usa três fontes, nesta ordem: nome de categoria vindo da planilha, regras "palavra-chave → categoria" do usuário, e o histórico de lançamentos já categorizados (descrição idêntica, depois por tokens). Ao confirmar uma importação, as categorias escolhidas viram regras automaticamente. Duplicados são detectados por `FITID` (OFX) ou por data + valor + descrição.

**Excel:** a primeira planilha é lida; a linha de cabeçalho é detectada por nomes como *Data*, *Descrição/Histórico*, *Valor* (opcionais: *Tipo*, *Categoria*). Sem cabeçalho, assume-se a ordem data | descrição | valor. Valores negativos ou coluna *Tipo* = débito viram despesas.

## API (resumo)

Todas as rotas exceto `/api/auth/*` exigem `Authorization: Bearer <token>`. Erros seguem RFC 9457 (`application/problem+json`), com `errors` por campo em validações.

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | Cadastro e login (retornam token + usuário) |
| GET | `/api/auth/me` | Usuário atual |
| GET/POST/PUT/DELETE | `/api/accounts` | Contas (saldo calculado dos lançamentos pagos) |
| GET/POST/PUT/DELETE | `/api/categories` | Categorias (cor + ícone) |
| GET | `/api/transactions?month=AAAA-MM&accountId&categoryId&type&paid&search` | Lançamentos do mês + resumo |
| POST/PUT/DELETE | `/api/transactions/{id}` | CRUD de lançamentos |
| PATCH | `/api/transactions/{id}/paid` | Marcar pago/pendente |
| GET/POST/PUT/DELETE | `/api/recurring-bills` | Contas recorrentes |
| POST | `/api/imports/preview` (multipart `file`, `accountId`) | Lê o arquivo e sugere categorias |
| POST | `/api/imports/confirm` | Cria os lançamentos selecionados |
| GET | `/api/imports` | Histórico de importações |
| DELETE | `/api/imports/{id}` | Exclui a importação e todos os lançamentos gerados por ela |
| GET/POST/DELETE | `/api/category-rules` | Regras de categorização |
| GET | `/api/dashboard?month=AAAA-MM` | Totais, categorias, vencidos, próximos 7 dias, 6 meses |

## Estrutura

```
backend/   Spring Boot (br.com.contaslt: auth, account, category, transaction, recurring, imports, dashboard)
frontend/  Vue (src/pages, src/components/app, src/components/ui (shadcn), src/stores, src/api)
docker-compose.yml  PostgreSQL 17
```
