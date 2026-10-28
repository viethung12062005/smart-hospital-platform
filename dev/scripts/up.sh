#!/usr/bin/env bash
# Dựng cụm kind + cài phụ thuộc. Dùng: scripts/up.sh [core]
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
PROFILE="${PROFILE:-${1:-core}}"
case "$PROFILE" in
  core) ;;
  full) ;;
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

log "Khởi tạo collection/index/validator của notification và ml (contracts/db/mongo-notification-ml.js, idempotent)"
k -n "$NAMESPACE" wait --for=condition=complete job/mongodb-rs-init --timeout 5m >/dev/null
k -n "$NAMESPACE" exec statefulset/mongodb -- mongosh --quiet "mongodb://localhost:27017/?directConnection=true" \
  --eval "$(cat "$DEV_DIR/../contracts/db/mongo-notification-ml.js")" >/dev/null

if [ "$PROFILE" = full ]; then
  log "Profile full: cài ClamAV, OpenSearch, Ollama, MLflow, Loki, Grafana, OTel Collector (cần ~10 GB RAM trống cho Docker)"
  helm --kube-context "$CTX" upgrade --install shp-full "$DEV_DIR/chart/shp-local-full" \
    --namespace "$NAMESPACE" --wait --timeout 15m
  log "Chưa có model LLM/embedding: chạy 'make -C dev pull-models' (tải vài GB, một lần)"
fi

"$(dirname "${BASH_SOURCE[0]}")/env.sh"
log "Xong. Xem chuỗi kết nối: dev/.local/connection.env · Tạo người dùng thử: make seed-users"
