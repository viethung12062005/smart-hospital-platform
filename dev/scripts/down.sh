#!/usr/bin/env bash
# Xóa cụm local (mất dữ liệu trong cụm). Mật khẩu ở dev/.local được giữ lại.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need kind "Cài kind."
kind delete cluster --name "$CLUSTER_NAME"
log "Đã xóa cụm $CLUSTER_NAME. Xóa luôn mật khẩu: rm -rf dev/.local"
