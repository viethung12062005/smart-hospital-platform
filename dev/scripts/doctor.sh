#!/usr/bin/env bash
# Kiểm tra máy dev đã đủ điều kiện chạy cụm local chưa. Dùng: scripts/doctor.sh [--quiet]
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
QUIET="${1:-}"

need docker  "Cài Docker Desktop (Windows/macOS) hoặc Docker Engine (Linux): https://docs.docker.com/get-docker/"
need kind    "Cài kind: https://kind.sigs.k8s.io/docs/user/quick-start/#installation"
need kubectl "Cài kubectl: https://kubernetes.io/docs/tasks/tools/"
need helm    "Cài Helm 3: https://helm.sh/docs/intro/install/"
need openssl "Cài openssl (Windows: dùng Git Bash hoặc WSL2)"
docker info >/dev/null 2>&1 || die "Docker chưa chạy. Hãy khởi động Docker rồi thử lại."

mem_gb=0
if [ -r /proc/meminfo ]; then
  mem_gb=$(awk '/MemTotal/ {printf "%d", $2/1024/1024}' /proc/meminfo)
elif command -v sysctl >/dev/null 2>&1; then
  mem_gb=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1024 / 1024 / 1024 ))
fi
docker_mem_gb=$(docker info --format '{{.MemTotal}}' 2>/dev/null | awk '{printf "%d", $1/1024/1024/1024}')

if [ "${docker_mem_gb:-0}" -gt 0 ] && [ "$docker_mem_gb" -lt 6 ]; then
  warn "Docker chỉ được cấp ${docker_mem_gb} GB RAM; profile core cần ~6 GB. Tăng trong Docker Desktop → Settings → Resources."
fi

PORTS="15432 27117 16379 35672 25672 10000 10001 18080"
busy=""
if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  for p in $PORTS; do
    if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then busy="$busy $p"; fi
  done
  [ -z "$busy" ] || warn "Các cổng đang bị chiếm:$busy — dừng chương trình đang dùng chúng hoặc đổi cổng trong dev/kind/cluster.yaml."

  # Windows (WSL2) tự giữ chỗ các dải cổng TCP ngẫu nhiên (Hyper-V/WinNAT, khác nhau theo máy); Docker không mở được cổng nằm trong dải đó.
  if command -v netsh.exe >/dev/null 2>&1; then
    ranges=$(netsh.exe interface ipv4 show excludedportrange protocol=tcp 2>/dev/null | tr -d '\r' | awk '$1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ {print $1, $2}')
    reserved=""
    for p in $PORTS; do
      while read -r lo hi; do
        [ -n "$lo" ] && [ "$p" -ge "$lo" ] && [ "$p" -le "$hi" ] && reserved="$reserved $p"
      done <<< "$ranges"
    done
    [ -z "$reserved" ] || die "Windows đang giữ chỗ các cổng:$reserved (không dùng được). Cách xử lý: (1) PowerShell chạy bằng quyền Admin: 'net stop winnat; net start winnat' rồi thử lại; hoặc (2) đổi hostPort tương ứng trong dev/kind/cluster.yaml, dev/scripts/env.sh và PORTS trong file này."
  fi
fi

[ "$QUIET" = "--quiet" ] || log "OK: docker, kind, kubectl, helm sẵn sàng (RAM máy: ${mem_gb} GB, Docker: ${docker_mem_gb} GB)."
