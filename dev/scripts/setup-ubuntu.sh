#!/usr/bin/env bash
# Prepare an Ubuntu machine (recommended: WSL2 on Windows) for coding and running the system locally.
# Idempotent: safe to run any number of times; steps already done are skipped.
#
# Usage (in an Ubuntu terminal):
#   bash dev/scripts/setup-ubuntu.sh
# Optional (environment variables): GIT_NAME="Your Name" GIT_EMAIL="you@example.com"   to avoid prompts
#                             SKIP_GH=1   skip installing the GitHub CLI
set -euo pipefail

JAVA_PKG="openjdk-21-jdk"
MAVEN_VERSION="3.9.16"
# SHA-512 of apache-maven-3.9.16-bin.tar.gz, from https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/
MAVEN_SHA512="831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6"
MAVEN_HOME_DIR="/opt/apache-maven-${MAVEN_VERSION}"
# Node.js LTS for the web app (web/). SHA-256 from https://nodejs.org/dist/v22.23.4/SHASUMS256.txt
NODE_VERSION="22.23.4"
NODE_SHA256_X64="1805bcd35e6815559bd2eb97db438d3fdb0eb3a5e6a977b7c1badca76337e9ce"
NODE_SHA256_ARM64="6287aa46b6c375d816e63ae66d024fbbfd70404825aabdfdf898a970871d2f4b"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m ok\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m !!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = Linux ] || die "This script is for Ubuntu/WSL2. On macOS install JDK 21, Maven 3.9, Python 3.12 and Node.js 22 with Homebrew."
grep -qi ubuntu /etc/os-release 2>/dev/null || warn "Not Ubuntu: the script uses apt and may not work correctly."

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  command -v sudo >/dev/null 2>&1 || die "Root or sudo privileges are required."
  SUDO="sudo"
fi

# ---- 1. Repository location ------------------------------------------------------------------------------------------
case "$PWD" in
  /mnt/*) warn "You are in $PWD (a Windows drive). Builds and tests will be slow and prone to file-permission errors. Clone the repo into a WSL directory, e.g. ~/work." ;;
esac

# ---- 2. System packages -----------------------------------------------------------------------------------------------
log "Installing system packages (git, make, curl, openssl, jq, unzip, xz, Python 3, JDK 21)"
export DEBIAN_FRONTEND=noninteractive
$SUDO apt-get update -qq
$SUDO apt-get install -y -qq --no-install-recommends \
  ca-certificates curl git make openssl jq unzip zip xz-utils build-essential \
  python3 python3-venv python3-pip "$JAVA_PKG" >/dev/null
ok "system packages"

# ---- 3. Maven 3.9 (Ubuntu 24.04 apt only ships 3.8) -------------------------------------------------------------------
if [ -x "$MAVEN_HOME_DIR/bin/mvn" ]; then
  ok "Maven $MAVEN_VERSION already present"
else
  log "Downloading Maven $MAVEN_VERSION (verifying SHA-512)"
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/maven.tgz" "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  echo "${MAVEN_SHA512}  $tmp/maven.tgz" | sha512sum -c - >/dev/null || die "Maven checksum mismatch: download again or report to the SA."
  $SUDO tar -xzf "$tmp/maven.tgz" -C /opt
  ok "Maven $MAVEN_VERSION"
fi
$SUDO ln -sfn "$MAVEN_HOME_DIR/bin/mvn" /usr/local/bin/mvn

# ---- 3b. Node.js LTS (web app). The apt version is too old: use the official build, verify SHA-256 ---------------------
case "$(uname -m)" in
  x86_64|amd64) node_arch=x64; node_sha="$NODE_SHA256_X64" ;;
  aarch64|arm64) node_arch=arm64; node_sha="$NODE_SHA256_ARM64" ;;
  *) die "CPU not supported for Node.js: $(uname -m)" ;;
esac
NODE_HOME_DIR="/opt/node-v${NODE_VERSION}-linux-${node_arch}"
if [ -x "$NODE_HOME_DIR/bin/node" ]; then
  ok "Node.js $NODE_VERSION already present"
else
  log "Downloading Node.js $NODE_VERSION (verifying SHA-256)"
  ntmp="$(mktemp -d)"
  curl -fsSL -o "$ntmp/node.tar.xz" "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${node_arch}.tar.xz"
  echo "${node_sha}  $ntmp/node.tar.xz" | sha256sum -c - >/dev/null || die "Node.js checksum mismatch: download again or report to the SA."
  $SUDO tar -xJf "$ntmp/node.tar.xz" -C /opt
  rm -rf "$ntmp"
  ok "Node.js $NODE_VERSION"
fi
for b in node npm npx; do $SUDO ln -sfn "$NODE_HOME_DIR/bin/$b" "/usr/local/bin/$b"; done

# ---- 4. GitHub CLI (GitHub login + git credential) --------------------------------------------------------------------
if [ -z "${SKIP_GH:-}" ] && ! command -v gh >/dev/null 2>&1; then
  log "Installing the GitHub CLI"
  $SUDO mkdir -p -m 755 /etc/apt/keyrings
  curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg | $SUDO tee /etc/apt/keyrings/githubcli-archive-keyring.gpg >/dev/null
  $SUDO chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
    | $SUDO tee /etc/apt/sources.list.d/github-cli.list >/dev/null
  $SUDO apt-get update -qq
  $SUDO apt-get install -y -qq gh >/dev/null
  ok "gh $(gh --version | head -1 | awk '{print $3}')"
elif command -v gh >/dev/null 2>&1; then
  ok "gh already present"
fi

# ---- 5. Git configuration ---------------------------------------------------------------------------------------------
log "Configuring git"
git config --global core.autocrlf input          # never write CRLF into the repo
git config --global init.defaultBranch main
git config --global push.autoSetupRemote true    # the first git push creates the remote branch automatically
git config --global pull.rebase false

name="$(git config --global user.name || true)"; email="$(git config --global user.email || true)"
[ -n "$name" ]  || name="${GIT_NAME:-}"
[ -n "$email" ] || email="${GIT_EMAIL:-}"
if [ -z "$name" ] || [ -z "$email" ]; then
  if [ -t 0 ]; then
    [ -n "$name" ]  || read -r -p "Display name on commits (e.g. Nguyen Van A): " name
    [ -n "$email" ] || read -r -p "Your GitHub email: " email
  else
    warn "No user.name/user.email yet (not an interactive terminal). Run: git config --global user.name \"...\" && git config --global user.email \"...\""
  fi
fi
[ -z "$name" ]  || git config --global user.name "$name"
[ -z "$email" ] || git config --global user.email "$email"
ok "git: $(git config --global user.name 2>/dev/null || echo '(not set)') <$(git config --global user.email 2>/dev/null || echo '-')>"

# ---- 6. Environment variables (JAVA_HOME) ----------------------------------------------------------------------------
marker="# >>> shp dev >>>"
if ! grep -qF "$marker" "$HOME/.bashrc" 2>/dev/null; then
  java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  {
    echo "$marker"
    echo "export JAVA_HOME=\"$java_home\""
    echo "export MAVEN_OPTS=\"-Xmx1g\""
    echo "# <<< shp dev <<<"
  } >> "$HOME/.bashrc"
  ok "added JAVA_HOME to ~/.bashrc (open a new terminal for it to take effect)"
else
  ok "~/.bashrc already configured"
fi

# ---- 7. Docker ------------------------------------------------------------------------------------------------------
docker_ok=1
if ! command -v docker >/dev/null 2>&1; then
  docker_ok=0; warn "The 'docker' command was not found in WSL. Open Docker Desktop → Settings → Resources → WSL integration → enable it for this Ubuntu distro, then reopen the terminal."
elif ! docker info >/dev/null 2>&1; then
  docker_ok=0; warn "'docker' exists but cannot reach the daemon. Start Docker Desktop (and enable WSL integration for Ubuntu)."
elif ! docker buildx version >/dev/null 2>&1; then
  docker_ok=0; warn "'docker buildx' is missing (needed to build images). With a self-installed Docker Engine: sudo apt-get install docker-buildx"
else
  ok "Docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
fi

# ---- 8. Summary ------------------------------------------------------------------------------------------------------
echo
log "Installed versions"
printf '  java    %s\n' "$(java -version 2>&1 | head -1)"
printf '  mvn     %s\n' "$(mvn -v 2>/dev/null | head -1)"
printf '  python  %s\n' "$(python3 --version)"
printf '  node    %s (npm %s)\n' "$(node --version)" "$(npm --version)"
printf '  git     %s\n' "$(git --version)"
command -v gh >/dev/null 2>&1 && printf '  gh      %s\n' "$(gh --version | head -1)"

echo
log "Next steps"
if command -v gh >/dev/null 2>&1 && ! gh auth status >/dev/null 2>&1; then
  echo "  1. Sign in to GitHub (the repo is private):  gh auth login   (choose GitHub.com → HTTPS → Login with a web browser)"
  echo "     then:                                gh auth setup-git"
fi
echo "  2. Clone the repo into a WSL directory:  mkdir -p ~/work && cd ~/work && git clone https://github.com/<owner>/smart-hospital-platform.git"
echo "  3. Open with VS Code (already inside WSL): cd smart-hospital-platform && code ."
echo "  4. Run the whole system:                make -C dev quickstart"
[ "$docker_ok" = 1 ] || { echo; warn "Docker is not ready (see the warning above): fix it before step 4."; exit 0; }
