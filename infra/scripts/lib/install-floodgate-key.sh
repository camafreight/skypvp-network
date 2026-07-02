#!/bin/sh
# Copy the shared Floodgate key into a plugins/floodgate directory.
install_floodgate_key() {
    floodgate_dir="${1:?floodgate plugins dir required}"
    key_src="${SPVP_FLOODGATE_KEY_FILE:-/opt/skypvp/floodgate-key/key.pem}"

    if [ ! -f "$key_src" ]; then
        echo "[entrypoint] Floodgate key not found at ${key_src}; Floodgate will generate one on first start (backends must share the proxy key)."
        return 0
    fi

    mkdir -p "$floodgate_dir"
    cp "$key_src" "$floodgate_dir/key.pem"
    echo "[entrypoint] Installed Floodgate key -> ${floodgate_dir}/key.pem"

    geyser_dir="$(dirname "$floodgate_dir")/geyser"
    mkdir -p "$geyser_dir"
    cp "$key_src" "$geyser_dir/key.pem"
    echo "[entrypoint] Installed Floodgate key -> ${geyser_dir}/key.pem"
}
