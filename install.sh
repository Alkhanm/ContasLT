#!/usr/bin/env bash
# Instala o ContasLT como serviço do usuário (systemd --user): faz o build, cria o serviço e o deixa
# ligado no boot. Sem sudo. Rode de novo depois de mudar o código: refaz o build e reinicia o serviço.
#
# Uso: ./install.sh [--port N] [--uninstall] | ./install.sh --worker [--uninstall]
#   --port N      porta HTTP do serviço; fica gravada em .env e vale nas próximas execuções
#   --uninstall   para o serviço, desliga do boot e remove o arquivo da unidade (banco e dados ficam)
#   --worker      em vez do app completo, instala só o worker da fila de comprovantes (contaslt-worker): lê as
#                 fotos enviadas ao servidor (Railway) com o Ollama deste computador. Precisa de CONTASLT_URL e
#                 WORKER_TOKEN no .env. Sem Docker nem npm. Com --uninstall remove o worker.
#
# Depois: systemctl --user status contaslt | journalctl --user -u contaslt -f | systemctl --user restart contaslt
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE=contaslt
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT="$UNIT_DIR/$SERVICE.service"
RUN_DIR="$ROOT/run"
# Porta: --port > variável PORT > PORT no .env > padrão (o mesmo do prod.sh).
PORT_ARG=
WORKER=0 UNINSTALL=0
PORT_FROM_ENV_FILE="$(grep -s '^PORT=' "$ROOT/.env" | tail -1 | cut -d= -f2- | tr -d '"' || true)"

if [[ -t 1 ]]; then C_INFO=$'\e[32m' C_WARN=$'\e[33m' C_OFF=$'\e[0m'; else C_INFO= C_WARN= C_OFF=; fi
info() { echo "${C_INFO}[install]${C_OFF} $*"; }
warn() { echo "${C_WARN}[install]${C_OFF} $*" >&2; }
fail() { warn "$*"; exit 1; }

while (( $# )); do
  case "$1" in
    --port) [[ "${2:-}" =~ ^[0-9]+$ ]] || fail "--port precisa de um número."; PORT_ARG="$2"; shift 2 ;;
    --worker) WORKER=1; shift ;;
    --uninstall) UNINSTALL=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) fail "Opção desconhecida: $1" ;;
  esac
done

# Copia o jar recém-compilado para run/ de forma atômica (rename): a JVM em execução continua lendo o jar antigo
# até reiniciar, em vez de quebrar quando o Maven sobrescreve target/*.jar.
copy_jar() {
  mkdir -p "$RUN_DIR"
  local built_jar
  built_jar="$(ls -t "$ROOT"/backend/target/*.jar | grep -v '\.original$' | head -1)"
  cp "$built_jar" "$RUN_DIR/contaslt.jar.new" && mv -f "$RUN_DIR/contaslt.jar.new" "$RUN_DIR/contaslt.jar"
}

# ---------- Worker da fila de comprovantes (--worker) ----------
# O servidor no Railway não tem modelo de visão: guarda a foto numa fila e este serviço, no seu computador,
# puxa cada tarefa, lê com o Ollama local e devolve o resultado. Só faz chamadas de saída.
if (( WORKER )); then
  WSERVICE=contaslt-worker
  WUNIT="$UNIT_DIR/$WSERVICE.service"
  if (( UNINSTALL )); then
    systemctl --user disable --now "$WSERVICE" 2>/dev/null || true
    rm -f "$WUNIT"; systemctl --user daemon-reload
    info "Worker removido."; exit 0
  fi
  command -v java >/dev/null 2>&1 || fail "Java 21 é necessário (sudo apt install openjdk-21-jdk)."
  java_major="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
  [[ "$java_major" =~ ^[0-9]+$ && "$java_major" -ge 21 ]] || fail "Java 21 ou mais novo é necessário (encontrado: $java_major)."
  for var in CONTASLT_URL WORKER_TOKEN; do
    grep -qs "^$var=." "$ROOT/.env" || fail "Falta $var no .env. Ex.: CONTASLT_URL=https://contaslt-production.up.railway.app e WORKER_TOKEN igual ao configurado no Railway (openssl rand -base64 32)."
  done
  chmod 600 "$ROOT/.env"
  if ! curl -fsS localhost:11434 >/dev/null 2>&1; then
    warn "Ollama não está respondendo em localhost:11434. O worker espera ele subir antes de pegar tarefas (ollama pull qwen3.5:9b-q8_0)."
  fi
  info "Build do backend (o worker é o mesmo jar, com --worker)..."
  (cd "$ROOT/backend" && ./mvnw -q -DskipTests package)
  copy_jar
  mkdir -p "$UNIT_DIR"
  cat > "$WUNIT" <<UNIT
[Unit]
Description=ContasLT worker (lê os comprovantes do servidor com o Ollama local)
After=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
WorkingDirectory=$ROOT
EnvironmentFile=$ROOT/.env
ExecStart=$(command -v java) -Xmx256m -jar $RUN_DIR/contaslt.jar --worker
Restart=always
RestartSec=15

[Install]
WantedBy=default.target
UNIT
  systemctl --user daemon-reload
  systemctl --user enable "$WSERVICE" >/dev/null 2>&1
  systemctl --user restart "$WSERVICE"
  if [[ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != yes ]]; then
    loginctl enable-linger "$USER" 2>/dev/null && info "Linger ativado: o worker sobe no boot mesmo sem login." \
      || warn "Para o worker subir no boot sem login: sudo loginctl enable-linger $USER"
  fi
  sleep 3
  if systemctl --user is-active --quiet "$WSERVICE"; then
    info "Worker no ar. Log: journalctl --user -u $WSERVICE -f"
    journalctl --user -u "$WSERVICE" -n 3 --no-pager -o cat 2>/dev/null | sed 's/^/    /'
  else
    warn "O worker não subiu. Últimas linhas (journalctl --user -u $WSERVICE):"
    journalctl --user -u "$WSERVICE" -n 15 --no-pager -o cat 2>/dev/null | sed 's/^/    /' >&2
    exit 1
  fi
  exit 0
fi

if (( UNINSTALL )); then
  systemctl --user disable --now "$SERVICE" 2>/dev/null || true
  rm -f "$UNIT"
  systemctl --user daemon-reload
  info "Serviço removido. O banco (docker 'contaslt-db') e a pasta data/ foram mantidos."
  exit 0
fi

# ---------- Pré-requisitos ----------
missing=()
command -v java >/dev/null 2>&1 || missing+=("java 21 (sudo apt install openjdk-21-jdk)")
command -v npm >/dev/null 2>&1 || missing+=("node 20+ com npm (https://nodejs.org ou nvm)")
command -v docker >/dev/null 2>&1 || missing+=("docker (https://docs.docker.com/engine/install/)")
command -v systemctl >/dev/null 2>&1 || missing+=("systemd")
if (( ${#missing[@]} )); then
  warn "Faltam dependências:"; printf '  - %s\n' "${missing[@]}" >&2; exit 1
fi
java_major="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
[[ "$java_major" =~ ^[0-9]+$ && "$java_major" -ge 21 ]] || fail "Java 21 ou mais novo é necessário (encontrado: $java_major)."
docker compose version >/dev/null 2>&1 || fail "O plugin 'docker compose' não está disponível."
docker info >/dev/null 2>&1 || fail "Sem acesso ao Docker. O daemon está rodando e seu usuário está no grupo docker? (sudo usermod -aG docker \$USER, depois faça login de novo)"
systemctl --user is-system-running >/dev/null 2>&1 || [[ "$(systemctl --user is-system-running 2>/dev/null)" == degraded ]] \
  || fail "O systemd de usuário não está acessível (systemctl --user)."
if ! command -v ollama >/dev/null 2>&1 && ! curl -fsS localhost:11434 >/dev/null 2>&1; then
  warn "Ollama não encontrado: a leitura de comprovantes por foto não vai funcionar até instalá-lo (https://ollama.com) e baixar o modelo (ollama pull qwen3.5:9b-q8_0)."
fi

# ---------- Porta ----------
PORT="${PORT_ARG:-${PORT:-${PORT_FROM_ENV_FILE:-8585}}}"
holder_pid="$(ss -ltnp 2>/dev/null | grep -E "[:.]$PORT " | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2 || true)"
if [[ -n "$holder_pid" && "$holder_pid" != "$(systemctl --user show "$SERVICE" -p MainPID --value 2>/dev/null)" ]]; then
  holder="$(ps -o comm= -p "$holder_pid" 2>/dev/null || echo processo) (pid $holder_pid)"
  fail "A porta $PORT já está em uso por $holder, que não é o serviço $SERVICE. Escolha outra: ./install.sh --port 8090"
fi
if [[ -n "$PORT_ARG" ]]; then
  # Grava no .env para o prod.sh e as próximas execuções do install.sh usarem a mesma porta.
  touch "$ROOT/.env"; chmod 600 "$ROOT/.env"
  if grep -q '^PORT=' "$ROOT/.env"; then sed -i "s/^PORT=.*/PORT=$PORT/" "$ROOT/.env"; else printf 'PORT=%s\n' "$PORT" >> "$ROOT/.env"; fi
fi

# ---------- Segredo do JWT ----------
if ! grep -qs '^JWT_SECRET=' "$ROOT/.env"; then
  info "Gerando JWT_SECRET em .env (tokens de login continuam válidos entre reinícios)..."
  printf 'JWT_SECRET=%s\n' "$(openssl rand -base64 48 2>/dev/null || head -c 48 /dev/urandom | base64 | tr -d '\n')" >> "$ROOT/.env"
  chmod 600 "$ROOT/.env"
fi

# ---------- Build ----------
info "Build do frontend e do backend..."
"$ROOT/prod.sh" --build-only

# Cópias estáveis para o serviço (ver copy_jar); o dist segue a mesma ideia.
copy_jar
# O worker (se instalado) usa o mesmo jar: reinicia para pegar a versão nova.
systemctl --user try-restart contaslt-worker 2>/dev/null || true
rm -rf "$RUN_DIR/dist.new" && cp -r "$ROOT/frontend/dist" "$RUN_DIR/dist.new"
if [[ -d "$RUN_DIR/dist" ]]; then mv "$RUN_DIR/dist" "$RUN_DIR/dist.old"; fi
mv "$RUN_DIR/dist.new" "$RUN_DIR/dist" && rm -rf "$RUN_DIR/dist.old"

# ---------- Serviço ----------
mkdir -p "$UNIT_DIR"
cat > "$UNIT" <<UNIT
[Unit]
Description=ContasLT (controle financeiro pessoal)
# O Docker pode ainda estar subindo no boot: o prod.sh falha e o systemd tenta de novo em 5 s.
After=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
WorkingDirectory=$ROOT
# A porta vem do .env (PORT=...), gravada pelo install.sh --port.
ExecStart=$ROOT/prod.sh --skip-build
Restart=on-failure
RestartSec=5
Environment=CONTASLT_JAR=$RUN_DIR/contaslt.jar
Environment=CONTASLT_DIST=$RUN_DIR/dist
Environment=TERM=dumb

[Install]
WantedBy=default.target
UNIT

systemctl --user daemon-reload
systemctl --user enable "$SERVICE" >/dev/null 2>&1
if systemctl --user is-active --quiet "$SERVICE"; then
  info "Reiniciando o serviço com o build novo..."
  systemctl --user restart "$SERVICE"
else
  info "Iniciando o serviço..."
  systemctl --user start "$SERVICE"
fi

# Sem linger, serviços de usuário só sobem quando você faz login; com linger, sobem no boot.
if [[ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != yes ]]; then
  if loginctl enable-linger "$USER" 2>/dev/null; then
    info "Linger ativado: o serviço sobe no boot mesmo sem login."
  else
    warn "Não consegui ativar o linger. Para o serviço subir no boot sem login, rode: sudo loginctl enable-linger $USER"
  fi
fi

# ---------- Confere ----------
for _ in $(seq 1 90); do
  curl -fsS "http://localhost:$PORT/actuator/health" >/dev/null 2>&1 && break
  systemctl --user is-active --quiet "$SERVICE" || systemctl --user is-activating --quiet "$SERVICE" 2>/dev/null || break
  sleep 2
done
if curl -fsS "http://localhost:$PORT/actuator/health" >/dev/null 2>&1; then
  info "ContasLT no ar: http://localhost:$PORT"
  for ip in $(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^(192\.168\.|10\.|100\.)'); do
    info "  na rede: http://$ip:$PORT"
  done
else
  warn "O serviço não respondeu. Últimas linhas do log (journalctl --user -u $SERVICE):"
  journalctl --user -u "$SERVICE" -n 15 --no-pager -o cat 2>/dev/null | sed 's/^/    /' >&2
  exit 1
fi
info "Comandos úteis: systemctl --user status $SERVICE | journalctl --user -u $SERVICE -f | ./install.sh --uninstall"
