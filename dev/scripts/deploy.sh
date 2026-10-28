#!/usr/bin/env bash
# Build image của service và deploy lên cụm kind. Dùng: scripts/deploy.sh <svc|all>
# Image không đẩy lên registry nào: build bằng Docker rồi nạp thẳng vào node kind.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ROOT="$(cd "$DEV_DIR/.." && pwd)"
APP_NS="shp-apps"
DOTNET="gateway scheduling notification clinical document audit"
PYTHON="ai ml"
CHART="${SHP_CHART_DIR:-$LOCAL_DIR/templates/charts/microservice}"

target="${1:-}"; [ -n "$target" ] || die "Dùng: make deploy SVC=<tên service|all>"
kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME" || die "Chưa có cụm. Chạy: make up"

# Chart dùng chung nằm ở repo shp-platform-templates; kéo về một lần (hoặc đặt SHP_CHART_DIR trỏ tới bản clone sẵn).
if [ ! -f "$CHART/Chart.yaml" ]; then
  log "Kéo chart microservice từ $TEMPLATES_GIT"
  rm -rf "$LOCAL_DIR/templates"
  git clone -q --depth 1 --branch "$TEMPLATES_REF" "$TEMPLATES_GIT" "$LOCAL_DIR/templates"
fi

ensure_credentials
k create namespace "$APP_NS" --dry-run=client -o yaml | k apply -f - >/dev/null
# Chuỗi kết nối TRONG cụm (tên DNS service của shp-deps), đưa vào Secret; values chung nạp bằng secretEnv.
d="$NAMESPACE.svc.cluster.local"
k -n "$APP_NS" create secret generic shp-local-conn \
  --from-literal=postgres="Host=postgres.$d;Port=5432;Username=shp;Password=$POSTGRES_PASSWORD" \
  --from-literal=mongodb="mongodb://mongodb.$d:27017/?directConnection=true" \
  --from-literal=redis="redis.$d:6379,password=$REDIS_PASSWORD" \
  --from-literal=rabbitmq="amqp://shp:$RABBITMQ_PASSWORD@rabbitmq.$d:5672/" \
  --from-literal=blob="DefaultEndpointsProtocol=http;AccountName=shp;AccountKey=$AZURITE_ACCOUNT_KEY;BlobEndpoint=http://azurite.$d:10000/shp;QueueEndpoint=http://azurite.$d:10001/shp" \
  --dry-run=client -o yaml | k apply -f - >/dev/null

deploy_one() {
  local svc="$1" ctx file
  case " $DOTNET " in
    *" $svc "*) ctx="$ROOT"; file="$ROOT/services/$svc/Dockerfile" ;;
    *) case " $PYTHON " in *" $svc "*) ctx="$ROOT/services/$svc"; file="$ctx/Dockerfile" ;; *) die "Service không tồn tại: $svc" ;; esac ;;
  esac
  log "[$svc] build image localhost/shp-$svc:dev"
  docker build -q -t "localhost/shp-$svc:dev" -f "$file" "$ctx" >/dev/null
  log "[$svc] nạp image vào cụm kind"
  kind load docker-image "localhost/shp-$svc:dev" --name "$CLUSTER_NAME" >/dev/null
  log "[$svc] helm upgrade --install"
  helm --kube-context "$CTX" upgrade --install "$svc" "$CHART" -n "$APP_NS" \
    -f "$DEV_DIR/chart/app-values-local.yaml" -f "$ROOT/services/$svc/deploy/values-local.yaml" \
    --set fullnameOverride="$svc" --set-string podAnnotations.redeployedAt="$(date +%s)" \
    --wait --timeout 5m >/dev/null
  log "[$svc] chạy OK"
}

if [ "$target" = all ]; then for s in $DOTNET $PYTHON; do deploy_one "$s"; done; else deploy_one "$target"; fi
log "Gọi thử qua gateway: make port-forward  rồi  curl http://localhost:18000/info"
