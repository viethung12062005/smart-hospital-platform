#!/usr/bin/env bash
# Chuẩn bị một máy Ubuntu (khuyên dùng: WSL2 trên Windows) để code và chạy hệ thống local.
# Idempotent: chạy lại bao nhiêu lần cũng được, bước nào đã xong sẽ được bỏ qua.
#
# Dùng (trong terminal Ubuntu):
#   bash dev/scripts/setup-ubuntu.sh
# Tùy chọn (biến môi trường): GIT_NAME="Tên Bạn" GIT_EMAIL="ban@example.com"   để khỏi bị hỏi
#                             SKIP_GH=1   bỏ qua cài GitHub CLI
set -euo pipefail

JAVA_PKG="openjdk-21-jdk"
MAVEN_VERSION="3.9.16"
# SHA-512 của apache-maven-3.9.16-bin.tar.gz, lấy từ https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/
MAVEN_SHA512="831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6"
MAVEN_HOME_DIR="/opt/apache-maven-${MAVEN_VERSION}"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m ok\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m !!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = Linux ] || die "Script này dành cho Ubuntu/WSL2. Trên macOS hãy cài JDK 21, Maven 3.9, Python 3.12 bằng Homebrew."
grep -qi ubuntu /etc/os-release 2>/dev/null || warn "Không phải Ubuntu: script dùng apt, có thể không chạy đúng."

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  command -v sudo >/dev/null 2>&1 || die "Cần quyền root hoặc sudo."
  SUDO="sudo"
fi

# ---- 1. Vị trí repo -------------------------------------------------------------------------------------------------
case "$PWD" in
  /mnt/*) warn "Bạn đang ở $PWD (ổ Windows). Build và test sẽ chậm và dễ lỗi quyền file. Hãy clone repo vào thư mục WSL, ví dụ ~/work." ;;
esac

# ---- 2. Gói hệ thống ------------------------------------------------------------------------------------------------
log "Cài gói hệ thống (git, make, curl, openssl, jq, unzip, Python 3, JDK 21)"
export DEBIAN_FRONTEND=noninteractive
$SUDO apt-get update -qq
$SUDO apt-get install -y -qq --no-install-recommends \
  ca-certificates curl git make openssl jq unzip zip build-essential \
  python3 python3-venv python3-pip "$JAVA_PKG" >/dev/null
ok "gói hệ thống"

# ---- 3. Maven 3.9 (apt của Ubuntu 24.04 chỉ có 3.8) ----------------------------------------------------------------
if [ -x "$MAVEN_HOME_DIR/bin/mvn" ]; then
  ok "Maven $MAVEN_VERSION đã có"
else
  log "Tải Maven $MAVEN_VERSION (kiểm SHA-512)"
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/maven.tgz" "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  echo "${MAVEN_SHA512}  $tmp/maven.tgz" | sha512sum -c - >/dev/null || die "Sai checksum Maven: tải lại hoặc báo SA."
  $SUDO tar -xzf "$tmp/maven.tgz" -C /opt
  ok "Maven $MAVEN_VERSION"
fi
$SUDO ln -sfn "$MAVEN_HOME_DIR/bin/mvn" /usr/local/bin/mvn

# ---- 4. GitHub CLI (đăng nhập GitHub + git credential) ---------------------------------------------------------------
if [ -z "${SKIP_GH:-}" ] && ! command -v gh >/dev/null 2>&1; then
  log "Cài GitHub CLI"
  $SUDO mkdir -p -m 755 /etc/apt/keyrings
  curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg | $SUDO tee /etc/apt/keyrings/githubcli-archive-keyring.gpg >/dev/null
  $SUDO chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
    | $SUDO tee /etc/apt/sources.list.d/github-cli.list >/dev/null
  $SUDO apt-get update -qq
  $SUDO apt-get install -y -qq gh >/dev/null
  ok "gh $(gh --version | head -1 | awk '{print $3}')"
elif command -v gh >/dev/null 2>&1; then
  ok "gh đã có"
fi

# ---- 5. Cấu hình git ------------------------------------------------------------------------------------------------
log "Cấu hình git"
git config --global core.autocrlf input          # không bao giờ ghi CRLF vào repo
git config --global init.defaultBranch main
git config --global push.autoSetupRemote true    # git push lần đầu tự tạo nhánh trên remote
git config --global pull.rebase false

name="$(git config --global user.name || true)"; email="$(git config --global user.email || true)"
[ -n "$name" ]  || name="${GIT_NAME:-}"
[ -n "$email" ] || email="${GIT_EMAIL:-}"
if [ -z "$name" ] || [ -z "$email" ]; then
  if [ -t 0 ]; then
    [ -n "$name" ]  || read -r -p "Tên hiển thị trên commit (vd. Nguyen Van A): " name
    [ -n "$email" ] || read -r -p "Email GitHub của bạn: " email
  else
    warn "Chưa có user.name/user.email (không phải terminal tương tác). Chạy: git config --global user.name \"...\" && git config --global user.email \"...\""
  fi
fi
[ -z "$name" ]  || git config --global user.name "$name"
[ -z "$email" ] || git config --global user.email "$email"
ok "git: $(git config --global user.name 2>/dev/null || echo '(chưa đặt tên)') <$(git config --global user.email 2>/dev/null || echo '-')>"

# ---- 6. Biến môi trường (JAVA_HOME) ---------------------------------------------------------------------------------
marker="# >>> shp dev >>>"
if ! grep -qF "$marker" "$HOME/.bashrc" 2>/dev/null; then
  java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  {
    echo "$marker"
    echo "export JAVA_HOME=\"$java_home\""
    echo "export MAVEN_OPTS=\"-Xmx1g\""
    echo "# <<< shp dev <<<"
  } >> "$HOME/.bashrc"
  ok "đã thêm JAVA_HOME vào ~/.bashrc (mở terminal mới để có hiệu lực)"
else
  ok "~/.bashrc đã cấu hình"
fi

# ---- 7. Docker ------------------------------------------------------------------------------------------------------
docker_ok=1
if ! command -v docker >/dev/null 2>&1; then
  docker_ok=0; warn "Chưa thấy lệnh 'docker' trong WSL. Mở Docker Desktop → Settings → Resources → WSL integration → bật cho bản Ubuntu này, rồi mở lại terminal."
elif ! docker info >/dev/null 2>&1; then
  docker_ok=0; warn "'docker' có nhưng chưa kết nối được daemon. Hãy khởi động Docker Desktop (và bật WSL integration cho Ubuntu)."
elif ! docker buildx version >/dev/null 2>&1; then
  docker_ok=0; warn "Thiếu 'docker buildx' (cần để build image). Với Docker Engine tự cài: sudo apt-get install docker-buildx"
else
  ok "Docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
fi

# ---- 8. Tổng kết ----------------------------------------------------------------------------------------------------
echo
log "Phiên bản đã cài"
printf '  java    %s\n' "$(java -version 2>&1 | head -1)"
printf '  mvn     %s\n' "$(mvn -v 2>/dev/null | head -1)"
printf '  python  %s\n' "$(python3 --version)"
printf '  git     %s\n' "$(git --version)"
command -v gh >/dev/null 2>&1 && printf '  gh      %s\n' "$(gh --version | head -1)"

echo
log "Bước tiếp theo"
if command -v gh >/dev/null 2>&1 && ! gh auth status >/dev/null 2>&1; then
  echo "  1. Đăng nhập GitHub (repo là private):  gh auth login   (chọn GitHub.com → HTTPS → Login with a web browser)"
  echo "     rồi:                                 gh auth setup-git"
fi
echo "  2. Clone repo vào thư mục WSL:          mkdir -p ~/work && cd ~/work && git clone https://github.com/<owner>/smart-hospital-platform.git"
echo "  3. Mở bằng VS Code (đã ở trong WSL):    cd smart-hospital-platform && code ."
echo "  4. Chạy toàn bộ hệ thống:               make -C dev quickstart"
[ "$docker_ok" = 1 ] || { echo; warn "Docker chưa sẵn sàng (xem cảnh báo ở trên): hãy xử lý trước bước 4."; exit 0; }
