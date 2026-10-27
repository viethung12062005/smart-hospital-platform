#!/usr/bin/env bash
# Dựng cụm kind + cài phụ thuộc. Dùng: scripts/up.sh [core]
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
PROFILE="${PROFILE:-${1:-core}}"
case "$PROFILE" in
  core) ;;
  full) die "Profile 'full' (OpenSearch, Ollama, ClamAV, MLflow, observability) thuộc task LOC-08, chưa có." ;;
  *)    die "Profile không hợp lệ: $PROFILE (core|full)" ;;
esac

"$(dirname "${BASH_SOURCE[0]}")/doctor.sh" --quiet

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  log "Cụm $CLUSTER_NAME đã tồn tại, giữ nguyên."
else
  log "Tạo cụm kind $CLUSTER_NAME"
  kind create cluster --config "$DEV_DIR/kind/cluster.yaml" --wait 120s
fi

k create namespace "$NAMESPACE" --dry-run=client -o yaml | k apply -f - >/dev/null
ensure_credentials
apply_credentials_secret

log "Cài phụ thuộc (PostgreSQL, MongoDB, Redis, RabbitMQ, Azurite, Keycloak) — lần đầu mất vài phút để kéo image"
helm --kube-context "$CTX" upgrade --install shp-deps "$DEV_DIR/chart/shp-local-deps" \
  --namespace "$NAMESPACE" --wait --timeout 10m

"$(dirname "${BASH_SOURCE[0]}")/env.sh"
log "Xong. Xem chuỗi kết nối: dev/.local/connection.env · Tạo người dùng thử: make seed-users"
