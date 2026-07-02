#!/bin/sh
# Velocity proxy entrypoint — Docker Compose and K3s images.
set -eu

. /opt/skypvp/lib/render-server-templates.sh
. /opt/skypvp/lib/install-floodgate-key.sh

REDIS_HOST="${SPVP_REDIS_HOST:-redis}"
REDIS_PORT="${SPVP_REDIS_PORT:-6379}"
REDIS_PASSWORD="${SPVP_REDIS_PASSWORD:-}"
POSTGRES_HOST="${SPVP_POSTGRES_HOST:-postgres}"
POSTGRES_PORT="${SPVP_POSTGRES_PORT:-5432}"
POSTGRES_PASSWORD="${SPVP_POSTGRES_PASSWORD:-change-me}"
IN_GAME_PLUGIN_SECRET="${SPVP_IN_GAME_PLUGIN_SECRET:-super-secret-change-me}"
SPVP_WEB_BACKEND_URL="${SPVP_WEB_BACKEND_URL:-http://skypvp-web-backend.skypvp-web.svc.cluster.local:8788}"
SPVP_PROXY_ID="${SPVP_PROXY_ID:-velocity-local}"
SPVP_DISABLE_TCPSHIELD="${SPVP_DISABLE_TCPSHIELD:-false}"
SPVP_GEYSER_BEDROCK_PORT="${SPVP_GEYSER_BEDROCK_PORT:-19132}"
SPVP_GEYSER_MOTD1="${SPVP_GEYSER_MOTD1:-SkyPvP Network}"
SPVP_GEYSER_MOTD2="${SPVP_GEYSER_MOTD2:-Bedrock + Java}"
SPVP_GEYSER_SERVER_NAME="${SPVP_GEYSER_SERVER_NAME:-SkyPvP}"
TEMPLATE_ROOT="${SPVP_SERVER_TEMPLATE_ROOT:-/opt/skypvp/server-template}"

export SPVP_REDIS_HOST="$REDIS_HOST"
export SPVP_REDIS_PORT="$REDIS_PORT"
export SPVP_REDIS_PASSWORD="$REDIS_PASSWORD"
export SPVP_POSTGRES_HOST="$POSTGRES_HOST"
export SPVP_POSTGRES_PORT="$POSTGRES_PORT"
export SPVP_POSTGRES_PASSWORD="$POSTGRES_PASSWORD"
export SPVP_IN_GAME_PLUGIN_SECRET="$IN_GAME_PLUGIN_SECRET"
export SPVP_REDIS_URL="$(build_redis_url)"
export SPVP_WEB_BACKEND_URL
export SPVP_PROXY_ID
export SPVP_SERVER_ID="${SPVP_SERVER_ID:-${SPVP_PROXY_ID:-velocity-local}}"

if [ "$SPVP_DISABLE_TCPSHIELD" = "true" ]; then
  export SPVP_TCPSHIELD_ONLY_ALLOW_PROXY="false"
  export SPVP_TCPSHIELD_GEYSER_SUPPORT="false"
else
  export SPVP_TCPSHIELD_ONLY_ALLOW_PROXY="${SPVP_TCPSHIELD_ONLY_ALLOW_PROXY:-true}"
  export SPVP_TCPSHIELD_GEYSER_SUPPORT="${SPVP_TCPSHIELD_GEYSER_SUPPORT:-false}"
fi

export SPVP_GEYSER_BEDROCK_PORT SPVP_GEYSER_MOTD1 SPVP_GEYSER_MOTD2 SPVP_GEYSER_SERVER_NAME

PROXY_TEMPLATE_VARS='$SPVP_SERVER_ID$SPVP_REDIS_HOST$SPVP_REDIS_PORT$SPVP_REDIS_PASSWORD$SPVP_POSTGRES_HOST$SPVP_POSTGRES_PORT$SPVP_POSTGRES_PASSWORD$SPVP_IN_GAME_PLUGIN_SECRET$SPVP_REDIS_URL$SPVP_WEB_BACKEND_URL$SPVP_PROXY_ID$SPVP_TCPSHIELD_ONLY_ALLOW_PROXY$SPVP_TCPSHIELD_GEYSER_SUPPORT$SPVP_GEYSER_BEDROCK_PORT$SPVP_GEYSER_MOTD1$SPVP_GEYSER_MOTD2$SPVP_GEYSER_SERVER_NAME'

shutdown() {
  if [ -n "${JAVA_PID:-}" ] && kill -0 "$JAVA_PID" 2>/dev/null; then
    kill -TERM "$JAVA_PID"
    wait "$JAVA_PID" || true
  fi
  exit 0
}

trap shutdown TERM INT

mkdir -p /data/runtime
render_server_templates "$TEMPLATE_ROOT" "/data/runtime" "$PROXY_TEMPLATE_VARS"
install_floodgate_key "/data/runtime/plugins/floodgate"

# Drop stale H2 data if switching to shared PostgreSQL storage.
rm -f /data/runtime/plugins/luckperms/luckperms-h2-v2.mv.db 2>/dev/null || true

cd /data/runtime

if [ -f /data/runtime/velocity.toml ]; then
  sed -i 's|^bind = .*|bind = "0.0.0.0:25565"|' /data/runtime/velocity.toml || true
  sed -i '/^[[:space:]]*lobby-1[[:space:]]*=.*/d' /data/runtime/velocity.toml || true
  sed -i '/^[[:space:]]*lobby[[:space:]]*=.*/d' /data/runtime/velocity.toml || true
  sed -i 's|^[[:space:]]*try = .*|        try = []|' /data/runtime/velocity.toml || true
  sed -i '/"lobby\.example\.com"[[:space:]]*=.*/d' /data/runtime/velocity.toml || true
  sed -i '/^[[:space:]]*survival-1[[:space:]]*=.*/d' /data/runtime/velocity.toml || true
  sed -i '/^[[:space:]]*minigame-1[[:space:]]*=.*/d' /data/runtime/velocity.toml || true
  sed -i '/"minigames\.example\.com"[[:space:]]*=.*/d' /data/runtime/velocity.toml || true
  sed -i '/"factions\.example\.com"[[:space:]]*=.*/d' /data/runtime/velocity.toml || true
fi

rm -f /data/runtime/plugins/skypvp-proxy-core.jar || true
if [ "$SPVP_DISABLE_TCPSHIELD" = "true" ]; then
  echo "[entrypoint] SPVP_DISABLE_TCPSHIELD=true — removing TCPShield jar for direct local connections"
  rm -f /data/runtime/plugins/TCPShield*.jar || true
fi

# Velocity + Geyser + plugins in 512M caused constant GC pressure -> proxy-side latency
# (rubberbanding for every player even when server MSPT was healthy).
PROXY_HEAP="${SPVP_PROXY_HEAP:-2G}"
java ${SPVP_PROXY_JAVA_OPTS:-} -Xms${PROXY_HEAP} -Xmx${PROXY_HEAP} -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -jar velocity.jar &
JAVA_PID=$!
wait "$JAVA_PID"
