#!/usr/bin/env bash
# Write connection strings for services running OUTSIDE the cluster (IDE, mvn spring-boot:run, uvicorn) to dev/.local/connection.env
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_credentials
out="$LOCAL_DIR/connection.env"
( umask 077
cat > "$out" <<EOT
# Generated automatically by dev/scripts/env.sh. Do NOT commit. Usage: set -a; . dev/.local/connection.env; set +a
# --- Java services (Spring Boot reads the SPRING_* variables directly) ---
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/scheduling
SPRING_DATASOURCE_USERNAME=shp
SPRING_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}
SPRING_MONGODB_URI=mongodb://localhost:27117/?directConnection=true
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=16379
SPRING_DATA_REDIS_PASSWORD=${REDIS_PASSWORD}
SPRING_RABBITMQ_HOST=localhost
SPRING_RABBITMQ_PORT=35672
SPRING_RABBITMQ_USERNAME=shp
SPRING_RABBITMQ_PASSWORD=${RABBITMQ_PASSWORD}
# clinical running in the cluster: kubectl --context kind-shp-local -n shp-apps port-forward svc/clinical 18081:80
SHP_CLINICAL_URL=http://localhost:18081
SHP_REQUIRE_MFA_FOR_STAFF=false
# --- Shared / Python ---
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
MONGODB_URI=mongodb://localhost:27117/?directConnection=true
RABBITMQ_URL=amqp://shp:${RABBITMQ_PASSWORD}@localhost:35672/
RABBITMQ_MANAGEMENT_URL=http://localhost:25672
AZURE_STORAGE_CONNECTION_STRING=DefaultEndpointsProtocol=http;AccountName=shp;AccountKey=${AZURITE_ACCOUNT_KEY};BlobEndpoint=http://localhost:10000/shp;QueueEndpoint=http://localhost:10001/shp
OIDC_AUTHORITY=http://localhost:18080/realms/shp
OIDC_AUDIENCE=shp-api
KEYCLOAK_ADMIN_URL=http://localhost:18080
KEYCLOAK_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD}
# --- Profile full (make up PROFILE=full) ---
CLAMD_HOST=localhost
CLAMD_PORT=13310
OPENSEARCH_URL=http://localhost:19200
OLLAMA_URL=http://localhost:21434
MLFLOW_TRACKING_URI=http://localhost:15000
GRAFANA_URL=http://localhost:13000
GRAFANA_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD}
# In the cluster: OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector.shp-deps:4318
EOT
)
log "Wrote $out"
