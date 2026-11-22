#!/usr/bin/env bash
# Create the kind cluster + install dependencies. Usage: scripts/up.sh [core]
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
PROFILE="${PROFILE:-${1:-core}}"
case "$PROFILE" in
  core) ;;
  full) ;;
  *)    die "Invalid profile: $PROFILE (core|full)" ;;
esac

"$(dirname "${BASH_SOURCE[0]}")/doctor.sh" --quiet

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  log "Cluster $CLUSTER_NAME already exists, keeping it."
else
  log "Creating kind cluster $CLUSTER_NAME"
  kind create cluster --config "$DEV_DIR/kind/cluster.yaml" --wait 120s
fi

k create namespace "$NAMESPACE" --dry-run=client -o yaml | k apply -f - >/dev/null
ensure_credentials
apply_credentials_secret

# Keycloak runs the image that carries the SHP login theme and extensions (identity/keycloak, IAM-03); it is built here and
# loaded straight into the kind node like the service images. Its image id is a pod annotation, so a rebuilt image restarts Keycloak.
log "Building the Keycloak image localhost/shp-keycloak:dev (identity/keycloak/Dockerfile)"
DOCKER_BUILDKIT=1 docker build -q -t localhost/shp-keycloak:dev -f "$DEV_DIR/../identity/keycloak/Dockerfile" "$DEV_DIR/.." >/dev/null
kind load docker-image localhost/shp-keycloak:dev --name "$CLUSTER_NAME" >/dev/null

log "Installing dependencies (PostgreSQL, MongoDB, Redis, RabbitMQ, Azurite, Keycloak, Mailpit) — the first run takes a few minutes to pull images"
helm --kube-context "$CTX" upgrade --install shp-deps "$DEV_DIR/chart/shp-local-deps" \
  --namespace "$NAMESPACE" --set-string keycloakImageId="$(docker image inspect -f '{{.Id}}' localhost/shp-keycloak:dev)" \
  --wait --timeout 10m

log "Initializing notification and ml collections/indexes/validators (contracts/db/mongo-notification-ml.js, idempotent)"
k -n "$NAMESPACE" wait --for=condition=complete job/mongodb-rs-init --timeout 5m >/dev/null
k -n "$NAMESPACE" exec statefulset/mongodb -- mongosh --quiet "mongodb://localhost:27017/?directConnection=true" \
  --eval "$(cat "$DEV_DIR/../contracts/db/mongo-notification-ml.js")" >/dev/null

if [ "$PROFILE" = full ]; then
  log "Profile full: installing ClamAV, OpenSearch, Ollama, MLflow, Loki, Grafana, OTel Collector (needs ~10 GB free RAM for Docker)"
  helm --kube-context "$CTX" upgrade --install shp-full "$DEV_DIR/chart/shp-local-full" \
    --namespace "$NAMESPACE" --wait --timeout 15m
  log "No LLM/embedding models yet: run 'make -C dev pull-models' (downloads a few GB, once)"
fi

"$(dirname "${BASH_SOURCE[0]}")/env.sh"
log "Done. See the connection strings: dev/.local/connection.env · Create test users: make seed-users"
