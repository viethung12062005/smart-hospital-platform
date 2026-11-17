#!/usr/bin/env bash
# Print the access token of a test user (for calling APIs during development). Usage: scripts/token.sh doctor.demo
# Staff roles need a TOTP code (IAM-10): enrol an authenticator at the first browser login, then TOTP=<code> scripts/token.sh doctor.demo
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
user="${1:-}"; [ -n "$user" ] || die "Usage: scripts/token.sh <username>   (e.g. doctor.demo)"
var="$(echo "${user//./_}_PASSWORD" | tr '[:lower:]' '[:upper:]')"
# shellcheck disable=SC1090
. "$LOCAL_DIR/users.env"
curl -sS -X POST "http://localhost:18080/realms/shp/protocol/openid-connect/token" \
  -d client_id=shp-test -d grant_type=password -d username="$user" ${TOTP:+-d totp="$TOTP"} --data-urlencode "password=${!var}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'
