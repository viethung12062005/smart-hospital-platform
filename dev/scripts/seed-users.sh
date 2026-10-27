#!/usr/bin/env bash
# Tạo người dùng thử nghiệm (DỮ LIỆU GIẢ) trong realm shp, mật khẩu ngẫu nhiên lưu ở dev/.local/users.env.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_credentials
users_file="$LOCAL_DIR/users.env"
touch "$users_file"; chmod 600 "$users_file"

kc() { k -n "$NAMESPACE" exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://localhost:8080 --realm master --user admin --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

# username:vai trò:họ:tên  (tên giả, không phải người thật)
for spec in "patient.demo:patient:Nguyen:Thi Lan" "doctor.demo:doctor:Le:Minh Quan" "nurse.demo:nurse:Tran:Thu Ha" "admin.demo:admin:Pham:Quang Huy" "dpo.demo:dpo:Vu:Hai Yen"; do
  IFS=: read -r name role last first <<<"$spec"
  if kc get users -r shp -q "username=$name" --fields username | grep -q "\"$name\""; then
    log "Đã có $name, bỏ qua"; continue
  fi
  pw="$(rand_hex)"
  kc create users -r shp -s "username=$name" -s enabled=true -s "email=$name@example.org" -s emailVerified=true \
     -s "lastName=$last" -s "firstName=$first" >/dev/null
  kc set-password -r shp --username "$name" --new-password "$pw"
  kc add-roles -r shp --uusername "$name" --rolename "$role"
  echo "${name//./_}_PASSWORD=$pw" | tr '[:lower:]' '[:upper:]' >> "$users_file"
  log "Tạo $name (role $role)"
done
log "Mật khẩu người dùng thử: $users_file. Lấy token: make token USER=doctor.demo"
