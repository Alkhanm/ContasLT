#!/usr/bin/env bash
# Instala o ContasLT como serviço do usuário (systemd --user): faz o build, cria o serviço e o deixa
# ligado no boot. Sem sudo. Rode de novo depois de mudar o código: refaz o build e reinicia o serviço.
#
# Uso: ./install.sh [--port N] [--uninstall]
#   --port N      porta HTTP do serviço (padrão 8080, ou a variável PORT)
#   --uninstall   para o serviço, desliga do boot e remove o arquivo da unidade (banco e dados ficam)
#
# Depois: systemctl --user status contaslt | journalctl --user -u contaslt -f | systemctl --user restart contaslt
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE=contaslt
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT="$UNIT_DIR/$SERVICE.service"
PORT="${PORT:-8080}"

if [[ -t 1 ]]; then C_INFO=$'\e[32m' C_WARN=$'\e[33m' C_OFF=$'\e[0m'; else C_INFO= C_WARN= C_OFF=; fi
info() { echo "${C_INFO}[install]${C_OFF} $*"; }
warn() { echo "${C_WARN}[install]${C_OFF} $*" >&2; }
fail() { warn "$*"; exit 1; }

while (( $# )); do
  case "$1" in
    --port) [[ "${2:-}" =~ ^[0-9]+$ ]] || fail "--port precisa de um número."; PORT="$2"; shift 2 ;;
    --uninstall)
      systemctl --user disable --now "$SERVICE" 2>/dev/null || true
      rm -f "$UNIT"
      systemctl --user daemon-reload
      info "Serviço removido. O banco (docker 'contaslt-db') e a pasta data/ foram mantidos."
      exit 0 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) fail "Opção desconhecida: $1" ;;
  esac
done

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
if ! systemctl --user is-active --quiet "$SERVICE" && ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$PORT\$"; then
  holder="$(ss -ltnp 2>/dev/null | grep -E "[:.]$PORT " | grep -oE 'users:\(\("[^"]+",pid=[0-9]+' | head -1 | sed -E 's/users:\(\("([^"]+)",pid=([0-9]+)/\1 (pid \2)/')"
  fail "A porta $PORT já está em uso por ${holder:-outro programa}. Escolha outra: ./install.sh --port 8090"
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

# ---------- Serviço ----------
mkdir -p "$UNIT_DIR"
cat > "$UNIT" <<UNIT
[Unit]
Description=ContasLT (controle financeiro pessoal, porta $PORT)
# O Docker pode ainda estar subindo no boot: o prod.sh falha e o systemd tenta de novo em 5 s.
After=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
WorkingDirectory=$ROOT
ExecStart=$ROOT/prod.sh --skip-build
Restart=on-failure
RestartSec=5
Environment=PORT=$PORT
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
  warn "O serviço não respondeu ainda. Veja: journalctl --user -u $SERVICE -n 50"
  exit 1
fi
info "Comandos úteis: systemctl --user status $SERVICE | journalctl --user -u $SERVICE -f | ./install.sh --uninstall"
