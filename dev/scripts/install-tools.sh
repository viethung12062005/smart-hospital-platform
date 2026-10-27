#!/usr/bin/env bash
# Tự cài kind, kubectl, helm (phiên bản ghim) vào dev/.local/bin — không cần sudo, không đụng máy.
# Mọi file tải về đều được kiểm sha256 theo checksum chính thức. Docker vẫn phải tự cài (Docker Desktop / Engine).
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

KIND_VERSION="v0.29.0"
KUBECTL_VERSION="v1.33.4"
HELM_VERSION="v3.18.6"

need curl "Cài curl."
case "$(uname -s)" in Linux) os=linux ;; Darwin) os=darwin ;; *) die "Windows: hãy chạy trong WSL2 (Ubuntu)." ;; esac
case "$(uname -m)" in x86_64|amd64) arch=amd64 ;; arm64|aarch64) arch=arm64 ;; *) die "CPU không hỗ trợ: $(uname -m)" ;; esac

BIN="$LOCAL_DIR/bin"; TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mkdir -p "$BIN"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | awk '{print $1}'; }
verify() { # verify <file> <expected>
  local got; got="$(sha256 "$1")"
  [ "$got" = "$2" ] || die "Sai checksum cho $1 (mong đợi $2, nhận $got)"
}
# installed <tool> <version> <lệnh in phiên bản...>
installed() { local t="$1" v="$2"; shift 2; [ -x "$BIN/$t" ] && "$BIN/$t" "$@" 2>/dev/null | grep -q "$v"; }

if installed kind "$KIND_VERSION" version; then log "kind $KIND_VERSION đã có"; else
  log "Tải kind $KIND_VERSION"
  u="https://kind.sigs.k8s.io/dl/$KIND_VERSION/kind-$os-$arch"
  curl -fsSLo "$TMP/kind" "$u"; verify "$TMP/kind" "$(curl -fsSL "$u.sha256sum" | awk '{print $1}')"
  install -m 0755 "$TMP/kind" "$BIN/kind"
fi

if installed kubectl "$KUBECTL_VERSION" version --client; then log "kubectl $KUBECTL_VERSION đã có"; else
  log "Tải kubectl $KUBECTL_VERSION"
  u="https://dl.k8s.io/release/$KUBECTL_VERSION/bin/$os/$arch/kubectl"
  curl -fsSLo "$TMP/kubectl" "$u"; verify "$TMP/kubectl" "$(curl -fsSL "$u.sha256")"
  install -m 0755 "$TMP/kubectl" "$BIN/kubectl"
fi

if installed helm "$HELM_VERSION" version; then log "helm $HELM_VERSION đã có"; else
  log "Tải helm $HELM_VERSION"
  u="https://get.helm.sh/helm-$HELM_VERSION-$os-$arch.tar.gz"
  curl -fsSLo "$TMP/helm.tgz" "$u"; verify "$TMP/helm.tgz" "$(curl -fsSL "$u.sha256sum" | awk '{print $1}')"
  tar -xzf "$TMP/helm.tgz" -C "$TMP"; install -m 0755 "$TMP/$os-$arch/helm" "$BIN/helm"
fi

log "Xong: $BIN (các lệnh make tự dùng thư mục này, không cần sửa PATH)"
