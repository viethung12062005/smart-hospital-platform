#!/usr/bin/env bash
# Delete the local cluster (data in the cluster is lost). Passwords in dev/.local are kept.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need kind "Install kind."
kind delete cluster --name "$CLUSTER_NAME"
log "Deleted cluster $CLUSTER_NAME. To delete the passwords too: rm -rf dev/.local"
