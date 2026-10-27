#!/usr/bin/env bash
# In access token của một người dùng thử (để gọi API khi phát triển). Dùng: scripts/token.sh doctor.demo
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
user="${1:-}"; [ -n "$user" ] || die "Dùng: scripts/token.sh <username>   (ví dụ doctor.demo)"
var="$(echo "${user//./_}_PASSWORD" | tr '[:lower:]' '[:upper:]')"
# shellcheck disable=SC1090
. "$LOCAL_DIR/users.env"
curl -sS -X POST "http://localhost:18080/realms/shp/protocol/openid-connect/token" \
  -d client_id=shp-test -d grant_type=password -d username="$user" --data-urlencode "password=${!var}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'
