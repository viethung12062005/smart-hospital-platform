#!/usr/bin/env bash
# Ghi chuỗi kết nối cho service chạy NGOÀI cụm (IDE, mvn spring-boot:run, uvicorn) vào dev/.local/connection.env
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_credentials
out="$LOCAL_DIR/connection.env"
( umask 077
cat > "$out" <<EOT
# Sinh tự động bởi dev/scripts/env.sh. KHÔNG commit. Dùng: set -a; . dev/.local/connection.env; set +a
# --- Service Java (Spring Boot đọc trực tiếp các biến SPRING_*) ---
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/scheduling
SPRING_DATASOURCE_USERNAME=shp
SPRING_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}
SPRING_MONGODB_URI=mongodb://localhost:27117/?directConnection=true
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=16379
SPRING_DATA_REDIS_PASSWORD=${REDIS_PASSWORD}
SPRING_RABBITMQ_HOST=localhost
SPRING_RABBITMQ_PORT=15673
SPRING_RABBITMQ_USERNAME=shp
SPRING_RABBITMQ_PASSWORD=${RABBITMQ_PASSWORD}
# clinical chạy trong cụm: kubectl --context kind-shp-local -n shp-apps port-forward svc/clinical 18081:80
SHP_CLINICAL_URL=http://localhost:18081
SHP_REQUIRE_MFA_FOR_STAFF=false
# --- Dùng chung / Python ---
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
MONGODB_URI=mongodb://localhost:27117/?directConnection=true
RABBITMQ_URL=amqp://shp:${RABBITMQ_PASSWORD}@localhost:15673/
RABBITMQ_MANAGEMENT_URL=http://localhost:25672
AZURE_STORAGE_CONNECTION_STRING=DefaultEndpointsProtocol=http;AccountName=shp;AccountKey=${AZURITE_ACCOUNT_KEY};BlobEndpoint=http://localhost:10000/shp;QueueEndpoint=http://localhost:10001/shp
OIDC_AUTHORITY=http://localhost:18080/realms/shp
OIDC_AUDIENCE=shp-api
KEYCLOAK_ADMIN_URL=http://localhost:18080
KEYCLOAK_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD}
EOT
)
log "Đã ghi $out"
