#!/usr/bin/env bash
# Hàm và hằng số dùng chung cho các script dev. Được `source` bởi script khác.
set -euo pipefail

DEV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="$DEV_DIR/.local"          # git-ignored: chứa mật khẩu sinh ngẫu nhiên và chuỗi kết nối
CLUSTER_NAME="shp-local"
NAMESPACE="shp-deps"
CTX="kind-${CLUSTER_NAME}"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "Thiếu '$1'. $2"; }
k()    { kubectl --context "$CTX" "$@"; }

rand_hex() { openssl rand -hex 16; }

# Sinh mật khẩu ngẫu nhiên lần đầu; các lần sau giữ nguyên để dữ liệu trong cụm vẫn dùng được.
ensure_credentials() {
  mkdir -p "$LOCAL_DIR"
  local f="$LOCAL_DIR/credentials.env"
  if [ ! -f "$f" ]; then
    ( umask 077
      {
        echo "POSTGRES_PASSWORD=$(rand_hex)"
        echo "REDIS_PASSWORD=$(rand_hex)"
        echo "RABBITMQ_PASSWORD=$(rand_hex)"
        echo "KEYCLOAK_ADMIN_PASSWORD=$(rand_hex)"
      } > "$f" )
    log "Đã sinh mật khẩu ngẫu nhiên vào $f (không commit file này)"
  fi
  # shellcheck disable=SC1090
  . "$f"
}

apply_credentials_secret() {
  k -n "$NAMESPACE" create secret generic shp-local-credentials \
    --from-literal=postgres-password="$POSTGRES_PASSWORD" \
    --from-literal=redis-password="$REDIS_PASSWORD" \
    --from-literal=rabbitmq-password="$RABBITMQ_PASSWORD" \
    --from-literal=keycloak-admin-password="$KEYCLOAK_ADMIN_PASSWORD" \
    --dry-run=client -o yaml | k apply -f - >/dev/null
}
