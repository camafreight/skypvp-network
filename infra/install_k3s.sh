#!/bin/bash
curl -sfL https://get.k3s.io | K3S_TOKEN="K1045ddaa83bdfd4f9f64915da203ff0a2d6e3d0a4b9d59d9ddd7b9a9052390f1f1::server:ecb77f54643599f3b4bffc48ff35e371" INSTALL_K3S_EXEC="server --cluster-init --cluster-reset --cluster-reset-restore-path=/tmp/snapshot.db" sh -
