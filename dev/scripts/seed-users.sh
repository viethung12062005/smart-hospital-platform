#!/usr/bin/env bash
# Create test users (FAKE DATA) in the shp realm; random passwords are stored in dev/.local/users.env.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_credentials
users_file="$LOCAL_DIR/users.env"
touch "$users_file"; chmod 600 "$users_file"

kc() { k -n "$NAMESPACE" exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://localhost:8080 --realm master --user admin --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

# username:role:last name:first name  (fake names, not real people)
for spec in "patient.demo:patient:Nguyen:Thi Lan" "doctor.demo:doctor:Le:Minh Quan" "nurse.demo:nurse:Tran:Thu Ha" "admin.demo:admin:Pham:Quang Huy" "dpo.demo:dpo:Vu:Hai Yen"; do
  IFS=: read -r name role last first <<<"$spec"
  if kc get users -r shp -q "username=$name" --fields username | grep -q "\"$name\""; then
    log "Already have $name, skipping"; continue
  fi
  pw="$(rand_hex)"
  kc create users -r shp -s "username=$name" -s enabled=true -s "email=$name@example.org" -s emailVerified=true \
     -s "lastName=$last" -s "firstName=$first" >/dev/null
  kc set-password -r shp --username "$name" --new-password "$pw"
  kc add-roles -r shp --uusername "$name" --rolename "$role"
  # Uppercase only the VARIABLE name; keep the password lowercase (uppercasing the whole line made token.sh always fail to log in).
  printf '%s_PASSWORD=%s\n' "$(echo "${name//./_}" | tr '[:lower:]' '[:upper:]')" "$pw" >> "$users_file"
  log "Created $name (role $role)"
done
log "Test user passwords: $users_file. Get a token: make token USER=doctor.demo"
