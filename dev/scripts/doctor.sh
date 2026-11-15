#!/usr/bin/env bash
# Check whether the dev machine is ready to run the local cluster. Usage: scripts/doctor.sh [--quiet]
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
QUIET="${1:-}"

need docker  "Install Docker Desktop (Windows/macOS) or Docker Engine (Linux): https://docs.docker.com/get-docker/"
need kind    "Install kind: https://kind.sigs.k8s.io/docs/user/quick-start/#installation"
need kubectl "Install kubectl: https://kubernetes.io/docs/tasks/tools/"
need helm    "Install Helm 3: https://helm.sh/docs/intro/install/"
need openssl "Install openssl (Windows: use Git Bash or WSL2)"
docker info >/dev/null 2>&1 || die "Docker is not running. Start Docker and try again."

mem_gb=0
if [ -r /proc/meminfo ]; then
  mem_gb=$(awk '/MemTotal/ {printf "%d", $2/1024/1024}' /proc/meminfo)
elif command -v sysctl >/dev/null 2>&1; then
  mem_gb=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1024 / 1024 / 1024 ))
fi
docker_mem_gb=$(docker info --format '{{.MemTotal}}' 2>/dev/null | awk '{printf "%d", $1/1024/1024/1024}')

if [ "${docker_mem_gb:-0}" -gt 0 ] && [ "$docker_mem_gb" -lt 6 ]; then
  warn "Docker is allocated only ${docker_mem_gb} GB of RAM; the core profile needs ~6 GB. Increase it in Docker Desktop → Settings → Resources."
fi

PORTS="15432 27117 16379 35672 25672 10000 10001 18080"
busy=""
if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  for p in $PORTS; do
    if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then busy="$busy $p"; fi
  done
  [ -z "$busy" ] || warn "Ports already in use:$busy — stop the programs using them or change the ports in dev/kind/cluster.yaml."

  # Windows (WSL2) reserves random TCP port ranges (Hyper-V/WinNAT, different per machine); Docker cannot open a port inside such a range.
  if command -v netsh.exe >/dev/null 2>&1; then
    ranges=$(netsh.exe interface ipv4 show excludedportrange protocol=tcp 2>/dev/null | tr -d '\r' | awk '$1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ {print $1, $2}')
    reserved=""
    for p in $PORTS; do
      while read -r lo hi; do
        [ -n "$lo" ] && [ "$p" -ge "$lo" ] && [ "$p" -le "$hi" ] && reserved="$reserved $p"
      done <<< "$ranges"
    done
    [ -z "$reserved" ] || die "Windows has reserved these ports:$reserved (unusable). Fixes: (1) run PowerShell as Administrator: 'net stop winnat; net start winnat' then try again; or (2) change the corresponding hostPort in dev/kind/cluster.yaml, dev/scripts/env.sh and PORTS in this file."
  fi
fi

[ "$QUIET" = "--quiet" ] || log "OK: docker, kind, kubectl, helm ready (machine RAM: ${mem_gb} GB, Docker: ${docker_mem_gb} GB)."
