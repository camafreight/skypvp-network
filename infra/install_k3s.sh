#!/bin/bash
set -euo pipefail

if [ -z "${K3S_TOKEN:-}" ]; then
  echo "K3S_TOKEN is required. Copy infra/k3s-install.env.example to infra/k3s-install.env and export it." >&2
  exit 1
fi

INSTALL_K3S_EXEC="${INSTALL_K3S_EXEC:-server --cluster-init}"

curl -sfL https://get.k3s.io | K3S_TOKEN="$K3S_TOKEN" INSTALL_K3S_EXEC="$INSTALL_K3S_EXEC" sh -
