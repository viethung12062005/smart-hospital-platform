#!/usr/bin/env bash
# Make the `svc-*` workload clients of the shp realm available to the services (FAKE/local only; no secret is stored in git).
# Keycloak generates each client secret when the client is created, so nothing in the repo knows it. This script:
#   1. creates the `internal.*` roles and the `svc-*` clients (with their role mappings) from the realm file when the
#      running realm lacks them (a cluster created before IAM-02 imported an older realm; re-runnable, never deletes);
#   2. reads each client secret from the Keycloak admin API and writes it straight into the Secret `svc-<name>-client`
#      (key `client-secret`) in shp-apps. deploy.sh injects it as OIDC_CLIENT_SECRET (dev/chart/app-values-local.yaml).
# Usage: scripts/service-clients.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need python3 "Install python3."
APP_NS="shp-apps"
REALM_FILE="$DEV_DIR/chart/shp-local-deps/files/realm-shp.json"
ensure_credentials
k create namespace "$APP_NS" --dry-run=client -o yaml | k apply -f - >/dev/null

kc() { k -n "$NAMESPACE" exec -i deploy/keycloak -- /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://localhost:8080 --realm master --user admin --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

# Read-only helper over the realm file: roles | clients | client <id> | sa-roles <id>
realm() {
  python3 -I -c '
import json, sys
r = json.load(open(sys.argv[1])); what = sys.argv[2]
svc = [c for c in r["clients"] if c["clientId"].startswith("svc-")]
if what == "roles":
    print("\n".join(x["name"] for x in r["roles"]["realm"] if x["name"].startswith("internal.")))
elif what == "clients":
    print("\n".join(c["clientId"] for c in svc))
elif what == "client":
    print(json.dumps(next(c for c in svc if c["clientId"] == sys.argv[3])))
elif what == "sa-roles":
    u = [u for u in r["users"] if u.get("serviceAccountClientId") == sys.argv[3]]
    print("\n".join(u[0]["realmRoles"]) if u else "")
' "$REALM_FILE" "$@"
}

[ -n "$(realm clients)" ] || die "$REALM_FILE has no svc-* client (is the IAM realm in this branch?)"

for role in $(realm roles); do
  kc get "roles/$role" -r shp >/dev/null 2>&1 || { kc create roles -r shp -s "name=$role" >/dev/null 2>&1; log "Created realm role $role"; }
done

for client in $(realm clients); do
  if [ -z "$(kc get clients -r shp -q "clientId=$client" --fields id | grep '"id"' || true)" ]; then
    realm client "$client" | kc create clients -r shp -f - >/dev/null 2>&1
    for role in $(realm sa-roles "$client"); do
      kc add-roles -r shp --uusername "service-account-$client" --rolename "$role" >/dev/null 2>&1
    done
    log "Created client $client"
  fi
  uuid="$(kc get clients -r shp -q "clientId=$client" --fields id | sed -n 's/.*"id" *: *"\([^"]*\)".*/\1/p')"
  secret="$(kc get "clients/$uuid/client-secret" -r shp --fields value | sed -n 's/.*"value" *: *"\([^"]*\)".*/\1/p')"
  [ -n "$secret" ] || die "No secret for $client"
  k -n "$APP_NS" create secret generic "$client-client" --from-literal=client-secret="$secret" \
    --dry-run=client -o yaml | k apply -f - >/dev/null
  unset secret
  log "Secret $client-client is in $APP_NS"
done
