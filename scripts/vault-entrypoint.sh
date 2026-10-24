#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# Vault entry-point UNIVERSAL — roda ANTES do processo do workload.
# Carrega os segredos do namespace do projeto (Vault KV v2, em VAULT_SECRET_PATH)
# para o ambiente e então faz `exec` no comando do container (CMD).
#
# Autenticação:
#   • dev  → VAULT_TOKEN (token estático, ex.: dev-root-token).
#   • prod → VAULT_ROLE  (login pelo método AWS IAM do Vault, usando a IAM role da
#            task — SEM token estático/segredo no state). Requer python3 OU node
#            na imagem (helpers vault-load.py / vault-load.mjs).
#
# Ordem de preferência p/ carregar: python3 → node (ambos fazem token-ou-IAM),
# depois fallbacks SÓ-TOKEN: php → curl/wget+jq → curl/wget+sed.
# Não bloqueia o boot: se falhar, segue com o env do compose.
# ─────────────────────────────────────────────────────────────────────────────
set -e

VAULT_ENV_FILE="${VAULT_ENV_FILE:-/tmp/vault.env}"
DIR="$(dirname "$0")"

_http() { # $1 = url → corpo na stdout (token-only)
  if command -v curl >/dev/null 2>&1; then
    curl -fsS -H "X-Vault-Token: ${VAULT_TOKEN}" "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O- --header="X-Vault-Token: ${VAULT_TOKEN}" "$1"
  else
    return 1
  fi
}

_emit() { # imprime linhas KEY='value' dos segredos
  # python3 / node: resolvem token estático OU login AWS IAM (prod) + fetch.
  if command -v python3 >/dev/null 2>&1 && [ -f "${DIR}/vault-load.py" ]; then
    python3 "${DIR}/vault-load.py"; return $?
  elif command -v node >/dev/null 2>&1 && [ -f "${DIR}/vault-load.mjs" ]; then
    node "${DIR}/vault-load.mjs"; return $?
  fi
  # Fallbacks SÓ-TOKEN (sem IAM): precisam de VAULT_TOKEN.
  if [ -z "${VAULT_TOKEN}" ]; then
    echo "[vault] sem python3/node p/ login IAM e sem VAULT_TOKEN — pulando" >&2
    return 1
  fi
  url="${VAULT_ADDR%/}/v1/${VAULT_SECRET_PATH}"
  if command -v php >/dev/null 2>&1; then
    php -r '$c=stream_context_create(["http"=>["header"=>"X-Vault-Token: ".getenv("VAULT_TOKEN"),"timeout"=>5]]);$j=json_decode(@file_get_contents($argv[1],false,$c),true);$d=$j["data"]["data"]??[];foreach($d as $k=>$v){echo $k."=".escapeshellarg((string)$v)."\n";}' "$url"
  elif command -v jq >/dev/null 2>&1; then
    _http "$url" | jq -r '.data.data // {} | to_entries[] | "\(.key)=\(.value|@sh)"'
  else
    _http "$url" | sed -n 's/.*"data"[^{]*{[^{]*{\(.*\)}[[:space:]]*,[[:space:]]*"metadata".*/\1/p' \
      | tr ',' '\n' \
      | sed -n "s/^[[:space:]]*\"\([^\"]*\)\"[[:space:]]*:[[:space:]]*\"\(.*\)\"[[:space:]]*$/\1='\2'/p"
  fi
}

# ── Modo Vault Agent (sidecar) — prod com imagem mínima, sem fetch no app ────
# (A2) O Agent autenticou pela identidade da cloud e RENDERIZOU os segredos num
# env file → o app só faz `source` (funciona em QUALQUER stack: go/java/.NET/php
# sem python/node/curl). Ver runtime/vault/agent/.
SECRETS_FILE="${VAULT_SECRETS_FILE:-/vault/secrets/app.env}"
if [ -f "${SECRETS_FILE}" ]; then
  echo "[vault] segredos via Vault Agent (${SECRETS_FILE})"
  set -a; . "${SECRETS_FILE}"; set +a
  exec "$@"
fi
# (A1) O Agent escreveu só um token num sink file → usa como VAULT_TOKEN.
# Exporta p/ os helpers (python/node) — subprocessos — também o enxergarem.
if [ -z "${VAULT_TOKEN}" ] && [ -n "${VAULT_TOKEN_FILE}" ] && [ -f "${VAULT_TOKEN_FILE}" ]; then
  VAULT_TOKEN="$(cat "${VAULT_TOKEN_FILE}")"
  export VAULT_TOKEN
fi

# Prossegue se há endereço + path + (token estático OU role IAM).
if [ -n "${VAULT_ADDR}" ] && [ -n "${VAULT_SECRET_PATH}" ] && { [ -n "${VAULT_TOKEN}" ] || [ -n "${VAULT_ROLE}" ]; }; then
  mode="token"; [ -z "${VAULT_TOKEN}" ] && [ -n "${VAULT_ROLE}" ] && mode="${VAULT_AUTH:-aws} (role)"
  echo "[vault] carregando ${VAULT_SECRET_PATH} de ${VAULT_ADDR} (auth: ${mode})"
  if _emit >"${VAULT_ENV_FILE}" 2>/dev/null && [ -s "${VAULT_ENV_FILE}" ]; then
    set -a; . "${VAULT_ENV_FILE}"; set +a
    echo "[vault] carregado(s) $(grep -c '=' "${VAULT_ENV_FILE}" 2>/dev/null || echo 0) valor(es) do Vault"
    rm -f "${VAULT_ENV_FILE}"
  else
    echo "[vault] indisponível/sem segredos — seguindo com o env do compose" >&2
  fi
else
  echo "[vault] sem VAULT_ADDR/SECRET_PATH e token/role — seguindo sem Vault" >&2
fi

# ── Fallback: .env do projeto (só fora de produção) ──────────────────────────
# Precedência final: Vault > env do compose > .env. O .env só PREENCHE chave ausente ou VAZIA — nunca
# sobrescreve valor definido (vazio conta como ausente: `KAFKA_BROKERS=""` degrada igual a não ter).
# Existe porque o Vault de dev é em memória: o que foi gravado à mão some a cada `make init`, e o
# serviço subia degradado sem dizer nada (ex.: KAFKA_BROKERS vazio = outbox inteiro DISPENSADO).
# Em produção NÃO roda: chave ausente no Vault lá é defeito de configuração e deve aparecer, não ser
# tapada por um arquivo local (que, de todo modo, o .dockerignore mantém fora da imagem).
# Parser próprio, sem `source`: o .env é dado, não script — nada nele é executado.
_dotenv() {
  n=0; chaves=""
  while IFS= read -r linha || [ -n "$linha" ]; do
    linha="${linha%"$(printf '\r')"}"
    case "$linha" in ''|'#'*) continue ;; esac
    linha="${linha#export }"
    chave="${linha%%=*}"
    [ "$chave" = "$linha" ] && continue                       # linha sem '='
    case "$chave" in ''|[0-9]*|*[!A-Za-z0-9_]*) continue ;; esac
    eval "definida=\${${chave}:+x}"                           # chave já validada: só [A-Za-z0-9_]
    [ -n "$definida" ] && continue
    valor="${linha#*=}"
    case "$valor" in
      \"*\") valor="${valor#\"}"; valor="${valor%\"}" ;;
      \'*\') valor="${valor#\'}"; valor="${valor%\'}" ;;
    esac
    export "${chave}=${valor}"
    n=$((n + 1)); chaves="${chaves} ${chave}"
  done < "$1"
  if [ "$n" -gt 0 ]; then
    echo "[env] ${n} valor(es) do .env (ausentes no Vault e no compose):${chaves}"
  fi
}
DOTENV_FILE="${DOTENV_FILE:-${DIR}/../.env}"
if [ "${APP_ENV}" != "production" ] && [ -f "${DOTENV_FILE}" ]; then
  _dotenv "${DOTENV_FILE}"
fi

exec "$@"
