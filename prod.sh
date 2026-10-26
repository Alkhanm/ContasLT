#!/usr/bin/env bash
# Roda a aplicação em modo produção, tudo numa porta só (8080): o backend serve o build do frontend.
# Sem servidor do Vite, sem proxy e sem recarregamentos inesperados ao voltar da câmera no celular.
# Ctrl+C encerra. O banco continua no Docker.
#
# Uso: ./prod.sh [--skip-build] [--no-db]
#   --skip-build   não refaz o build do frontend nem o jar (usa o que já existe)
#   --no-db        não mexe no Docker (banco já está rodando em outro lugar)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${PORT:-8080}"

BUILD=1 WITH_DB=1
for arg in "$@"; do
  case "$arg" in
    --skip-build) BUILD=0 ;;
    --no-db) WITH_DB=0 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "Opção desconhecida: $arg" >&2; exit 1 ;;
  esac
done

if [[ -t 1 ]]; then C_INFO=$'\e[32m' C_WARN=$'\e[33m' C_OFF=$'\e[0m'; else C_INFO= C_WARN= C_OFF=; fi
info() { echo "${C_INFO}[prod]${C_OFF} $*"; }
warn() { echo "${C_WARN}[prod]${C_OFF} $*" >&2; }
need() { command -v "$1" >/dev/null 2>&1 || { warn "'$1' não encontrado no PATH."; exit 1; }; }

# Variáveis opcionais (JWT_SECRET, OLLAMA_MODEL, PORT...) podem ficar num .env na raiz (ignorado pelo git).
if [[ -f "$ROOT/.env" ]]; then
  set -a; # shellcheck disable=SC1091
  source "$ROOT/.env"; set +a
fi

need java
JAR="$(ls -t "$ROOT"/backend/target/*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
DIST="$ROOT/frontend/dist"

# ---------- Build ----------
if (( BUILD )); then
  need npm
  if [[ ! -d "$ROOT/frontend/node_modules" ]] \
     || [[ "$ROOT/frontend/package-lock.json" -nt "$ROOT/frontend/node_modules/.package-lock.json" ]]; then
    info "Instalando dependências do frontend..."
    (cd "$ROOT/frontend" && npm install)
  fi
  info "Build do frontend (vue-tsc + vite build)..."
  (cd "$ROOT/frontend" && npm run build)
  info "Build do backend (mvnw package, sem testes)..."
  (cd "$ROOT/backend" && ./mvnw -q -DskipTests package)
  JAR="$(ls -t "$ROOT"/backend/target/*.jar | grep -v '\.original$' | head -1)"
else
  [[ -n "$JAR" ]] || { warn "Nenhum jar em backend/target. Rode sem --skip-build."; exit 1; }
  [[ -f "$DIST/index.html" ]] || { warn "Nenhum build em frontend/dist. Rode sem --skip-build."; exit 1; }
fi

# ---------- Banco ----------
if (( WITH_DB )); then
  need docker
  info "Subindo o PostgreSQL..."
  docker compose -f "$ROOT/docker-compose.yml" up -d db
  for _ in $(seq 1 60); do
    status="$(docker inspect --format '{{.State.Health.Status}}' contaslt-db 2>/dev/null || echo starting)"
    [[ "$status" == healthy ]] && break
    sleep 1
  done
  if [[ "$status" != healthy ]]; then
    warn "O banco não ficou saudável a tempo (status: $status). Veja 'docker logs contaslt-db'."
    exit 1
  fi
fi

# ---------- Aplicação ----------
if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$PORT\$"; then
  warn "A porta $PORT já está em uso. Encerre o processo antigo (dev.sh?) ou defina PORT=outra."
  exit 1
fi
export SPA_DIR="$DIST"
export STORAGE_DIR="${STORAGE_DIR:-$ROOT/data}"
export PORT

info "Iniciando em http://localhost:$PORT"
# Endereços IPv4 da rede local e do Tailscale (ignora bridges do Docker e IPv6).
for ip in $(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^(192\.168\.|10\.|100\.)'); do
  info "  na rede: http://$ip:$PORT"
done
info "Ctrl+C encerra."
exec java -jar "$JAR"
