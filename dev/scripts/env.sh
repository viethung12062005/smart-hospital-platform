#!/usr/bin/env bash
# Ghi chuỗi kết nối cho service chạy NGOÀI cụm (IDE, dotnet run, uvicorn) vào dev/.local/connection.env
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_credentials
out="$LOCAL_DIR/connection.env"
( umask 077
cat > "$out" <<EOT
# Sinh tự động bởi dev/scripts/env.sh. KHÔNG commit. Dùng: set -a; . dev/.local/connection.env; set +a
ConnectionStrings__Postgres=Host=localhost;Port=15432;Username=shp;Password=${POSTGRES_PASSWORD};Database=scheduling
POSTGRES_HOST=localhost
POSTGRES_PORT=15432
POSTGRES_USER=shp
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
# Các database: scheduling, notification, ml
MONGODB_URI=mongodb://localhost:27117/?directConnection=true
REDIS_CONNECTION=localhost:16379,password=${REDIS_PASSWORD}
RABBITMQ_URL=amqp://shp:${RABBITMQ_PASSWORD}@localhost:15673/
RABBITMQ_MANAGEMENT_URL=http://localhost:25672
AZURE_STORAGE_CONNECTION_STRING=UseDevelopmentStorage=true
OIDC_AUTHORITY=http://localhost:18080/realms/shp
OIDC_AUDIENCE=shp-api
KEYCLOAK_ADMIN_URL=http://localhost:18080
KEYCLOAK_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD}
EOT
)
log "Đã ghi $out"
