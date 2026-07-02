#!/bin/bash
set -euo pipefail

if [ -z "${K3S_URL:-}" ] || [ -z "${K3S_TOKEN:-}" ]; then
  echo "K3S_URL and K3S_TOKEN are required. Copy infra/k3s-install.env.example to infra/k3s-install.env and export it." >&2
  exit 1
fi

curl -sfL https://get.k3s.io | K3S_URL="$K3S_URL" K3S_TOKEN="$K3S_TOKEN" sh -
