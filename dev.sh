#!/usr/bin/env bash
# Sobe o ambiente de desenvolvimento completo: banco (Docker), backend (Spring Boot) e frontend (Vite).
# Ctrl+C encerra tudo.
#
# Uso: ./dev.sh [--no-db] [--install] [--backend-only | --frontend-only]
#   --no-db           não mexe no Docker (banco já está rodando em outro lugar)
#   --install         força "npm install" no frontend
#   --backend-only    sobe só banco + backend
#   --frontend-only   sobe só o frontend
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_PORT="${PORT:-8080}"
FRONTEND_PORT=5173

WITH_DB=1 WITH_BACKEND=1 WITH_FRONTEND=1 FORCE_INSTALL=0
for arg in "$@"; do
  case "$arg" in
    --no-db) WITH_DB=0 ;;
    --install) FORCE_INSTALL=1 ;;
    --backend-only) WITH_FRONTEND=0 ;;
    --frontend-only) WITH_BACKEND=0; WITH_DB=0 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "Opção desconhecida: $arg" >&2; exit 1 ;;
  esac
done

if [[ -t 1 ]]; then
  C_BACK=$'\e[36m' C_FRONT=$'\e[35m' C_INFO=$'\e[32m' C_WARN=$'\e[33m' C_OFF=$'\e[0m'
else
  C_BACK= C_FRONT= C_INFO= C_WARN= C_OFF=
fi
info() { echo "${C_INFO}[dev]${C_OFF} $*"; }
warn() { echo "${C_WARN}[dev]${C_OFF} $*" >&2; }
prefix() { local tag="$1" color="$2"; sed -u "s/^/${color}[${tag}]${C_OFF} /"; }

need() { command -v "$1" >/dev/null 2>&1 || { warn "'$1' não encontrado no PATH."; exit 1; }; }
port_busy() { ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"; }

PIDS=() HEALTH_PID=
cleanup() {
  trap - INT TERM EXIT
  set +e
  echo
  info "Encerrando..."
  [[ -n "$HEALTH_PID" ]] && kill "$HEALTH_PID" 2>/dev/null
  for pid in "${PIDS[@]}"; do
    kill -- -"$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  info "Pronto. (O banco continua rodando no Docker; use 'docker compose stop db' para parar.)"
}
trap cleanup INT TERM EXIT

# ---------- Banco ----------
if (( WITH_DB )); then
  need docker
  info "Subindo o PostgreSQL (docker compose up -d db)..."
  docker compose -f "$ROOT/docker-compose.yml" up -d db
  info "Aguardando o banco ficar saudável..."
  for _ in $(seq 1 60); do
    status="$(docker inspect --format '{{.State.Health.Status}}' contaslt-db 2>/dev/null || echo starting)"
    [[ "$status" == healthy ]] && break
    sleep 1
  done
  if [[ "$status" != healthy ]]; then
    warn "O banco não ficou saudável a tempo (status: $status). Veja 'docker logs contaslt-db'."
    exit 1
  fi
  info "Banco pronto em localhost:5432."
fi

# ---------- Backend ----------
if (( WITH_BACKEND )); then
  need java
  if port_busy "$BACKEND_PORT"; then
    warn "A porta $BACKEND_PORT já está em uso. Se for o serviço instalado: 'systemctl --user stop contaslt'. Ou defina PORT=outra."
    exit 1
  fi
  # Guarda comprovantes na pasta data/ da raiz, independentemente de onde o backend é iniciado.
  export STORAGE_DIR="${STORAGE_DIR:-$ROOT/data}"
  info "Iniciando o backend (Spring Boot, porta $BACKEND_PORT)..."
  (cd "$ROOT/backend" && exec setsid ./mvnw -q spring-boot:run) > >(prefix backend "$C_BACK") 2>&1 &
  PIDS+=("$!")
fi

# ---------- Frontend ----------
if (( WITH_FRONTEND )); then
  need npm
  if port_busy "$FRONTEND_PORT"; then
    warn "A porta $FRONTEND_PORT já está em uso. Encerre o Vite antigo antes."
    exit 1
  fi
  if (( FORCE_INSTALL )) || [[ ! -d "$ROOT/frontend/node_modules" ]] \
     || [[ "$ROOT/frontend/package-lock.json" -nt "$ROOT/frontend/node_modules/.package-lock.json" ]]; then
    info "Instalando dependências do frontend (npm install)..."
    (cd "$ROOT/frontend" && npm install) 2>&1 | prefix frontend "$C_FRONT"
  fi
  info "Iniciando o frontend (Vite, porta $FRONTEND_PORT)..."
  (cd "$ROOT/frontend" && exec setsid npm run dev) > >(prefix frontend "$C_FRONT") 2>&1 &
  PIDS+=("$!")
fi

# ---------- Aviso quando o backend responder ----------
if (( WITH_BACKEND )); then
  (
    for _ in $(seq 1 180); do
      if curl -fsS "http://localhost:$BACKEND_PORT/actuator/health" >/dev/null 2>&1; then
        echo "${C_INFO}[dev]${C_OFF} Backend respondendo em http://localhost:$BACKEND_PORT"
        if (( WITH_FRONTEND )); then
          echo "${C_INFO}[dev]${C_OFF} Abra http://localhost:$FRONTEND_PORT"
        fi
        exit 0
      fi
      sleep 1
    done
    echo "${C_WARN}[dev]${C_OFF} Backend ainda não respondeu após 3 min; confira os logs acima." >&2
  ) &
  HEALTH_PID=$!
fi

info "Ctrl+C encerra backend e frontend."
# Encerra tudo assim que o primeiro serviço principal cair.
wait -n "${PIDS[@]}"
