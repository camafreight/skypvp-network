#!/bin/sh
# Render config/server-templates/* into /data/runtime.
# Text configs use envsubst; binaries (jars, etc.) are copied as-is.
set -eu

ensure_envsubst() {
    if command -v envsubst >/dev/null 2>&1; then
        return 0
    fi
    if command -v apt-get >/dev/null 2>&1; then
        echo "[render-templates] Installing gettext-base for envsubst..."
        apt-get update -qq
        apt-get install -y --no-install-recommends gettext-base >/dev/null
        rm -rf /var/lib/apt/lists/*
        return 0
    fi
    echo "[render-templates] envsubst not found and apt-get unavailable" >&2
    return 1
}

_is_text_template() {
    case "$1" in
        *.jar|*.zip|*.gz|*.png|*.jpg|*.jpeg|*.gif|*.webp|*.ico|*.mca|*.dat|*.lock|*.nbt|*.class|*.bin|*.so|*.dll)
            return 1
            ;;
        *)
            return 0
            ;;
    esac
}

render_server_templates() {
    template_root="${1:?template root required}"
    runtime_root="${2:?runtime root required}"
    var_list="${3:?envsubst variable list required}"

    if [ ! -d "$template_root" ]; then
        echo "[render-templates] No template at ${template_root}, skipping."
        return 0
    fi

    ensure_envsubst

    echo "[render-templates] Applying ${template_root} -> ${runtime_root}"
    find "$template_root" -type f | sort | while read -r src; do
        rel="${src#${template_root}/}"
        dst="${runtime_root}/${rel}"
        mkdir -p "$(dirname "$dst")"
        if _is_text_template "$rel"; then
            envsubst "$var_list" < "$src" > "$dst"
        else
            cp "$src" "$dst"
        fi
        echo "[render-templates]   ${rel}"
    done
}

build_redis_url() {
    host="${SPVP_REDIS_HOST:-redis}"
    port="${SPVP_REDIS_PORT:-6379}"
    password="${SPVP_REDIS_PASSWORD:-}"
    if [ -n "$password" ]; then
        printf 'redis://:%s@%s:%s' "$password" "$host" "$port"
    else
        printf 'redis://%s:%s' "$host" "$port"
    fi
}
