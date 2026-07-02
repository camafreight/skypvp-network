#!/bin/sh
# Paper game-server entrypoint — Docker Compose and K3s images.
set -eu

. /opt/skypvp/lib/render-server-templates.sh
. /opt/skypvp/lib/install-floodgate-key.sh

# Aikar's flags (AlwaysPreTouch, G1 sizing) assume Xms == Xmx; unequal values cause
# heap-resize pauses that show up as lag spikes/rubberbanding. Default MIN to MAX.
JAVA_MAX="${SPVP_JAVA_MAX:-1G}"
JAVA_MIN="${SPVP_JAVA_MIN:-$JAVA_MAX}"
REDIS_HOST="${SPVP_REDIS_HOST:-redis}"
REDIS_PORT="${SPVP_REDIS_PORT:-6379}"
REDIS_PASSWORD="${SPVP_REDIS_PASSWORD:-}"
POSTGRES_HOST="${SPVP_POSTGRES_HOST:-postgres}"
POSTGRES_PORT="${SPVP_POSTGRES_PORT:-5432}"
POSTGRES_PASSWORD="${SPVP_POSTGRES_PASSWORD:-change-me}"
PRESET_ROOT="${SPVP_PRESET_ROOT:-/opt/skypvp/world-templates}"
PRESET_ID="${SPVP_PRESET_ID:-}"
IN_GAME_PLUGIN_SECRET="${SPVP_IN_GAME_PLUGIN_SECRET:-super-secret-change-me}"
SPVP_WEB_BACKEND_URL="${SPVP_WEB_BACKEND_URL:-http://skypvp-web-backend.skypvp-web.svc.cluster.local:8788}"
CHAT_MODERATION_ENABLED="${SPVP_CHAT_MODERATION_ENABLED:-false}"
CHAT_MODERATION_ENDPOINT="${SPVP_CHAT_MODERATION_ENDPOINT:-}"
CHAT_MODERATION_API_KEY="${SPVP_CHAT_MODERATION_API_KEY:-}"
CHAT_TRANSLATION_ENABLED="${SPVP_CHAT_TRANSLATION_ENABLED:-false}"
CHAT_TRANSLATION_ENDPOINT="${SPVP_CHAT_TRANSLATION_ENDPOINT:-}"
CHAT_TRANSLATION_API_KEY="${SPVP_CHAT_TRANSLATION_API_KEY:-}"
CHAT_TRANSLATION_REGION="${SPVP_CHAT_TRANSLATION_REGION:-}"
CHAT_TRANSLATION_DEBUG="${SPVP_CHAT_TRANSLATION_DEBUG:-false}"
CONSOLE_PIPE="/tmp/skypvp-paper-console.pipe"
TEMPLATE_ROOT="${SPVP_SERVER_TEMPLATE_ROOT:-/opt/skypvp/server-template}"

if [ -n "${POD_NAME:-}" ]; then
  if echo "$POD_NAME" | grep -qE '.*-[0-9]+$'; then
    base_name="${POD_NAME#skypvp-}"
    index="${base_name##*-}"
    prefix="${base_name%-*}"
    new_index=$((index + 1))
    export SPVP_SERVER_ID="${prefix}-${new_index}"
  else
    export SPVP_SERVER_ID="$POD_NAME"
  fi
fi

export SPVP_SERVER_ROLE="${SPVP_SERVER_ROLE:?SPVP_SERVER_ROLE is required}"
export SPVP_PRESET_ID
export SPVP_PRESET_ROOT
if [ -n "${SPVP_MAP_TEMPLATE_ROOT:-}" ]; then
  export SPVP_MAP_TEMPLATE_ROOT
fi
export SPVP_REDIS_HOST="$REDIS_HOST"
export SPVP_REDIS_PORT="$REDIS_PORT"
export SPVP_REDIS_PASSWORD="$REDIS_PASSWORD"
export SPVP_POSTGRES_HOST="$POSTGRES_HOST"
export SPVP_POSTGRES_PORT="$POSTGRES_PORT"
export SPVP_POSTGRES_DATABASE="${SPVP_POSTGRES_DATABASE:-skypvp_network}"
export SPVP_POSTGRES_USERNAME="${SPVP_POSTGRES_USERNAME:-skypvp}"
export SPVP_POSTGRES_PASSWORD="$POSTGRES_PASSWORD"
export SPVP_IN_GAME_PLUGIN_SECRET="$IN_GAME_PLUGIN_SECRET"
export SPVP_WEB_BACKEND_URL="$SPVP_WEB_BACKEND_URL"
export SPVP_CHAT_MODERATION_ENABLED="$CHAT_MODERATION_ENABLED"
export SPVP_CHAT_MODERATION_ENDPOINT="$CHAT_MODERATION_ENDPOINT"
export SPVP_CHAT_MODERATION_API_KEY="$CHAT_MODERATION_API_KEY"
export SPVP_CHAT_TRANSLATION_ENABLED="$CHAT_TRANSLATION_ENABLED"
export SPVP_CHAT_TRANSLATION_ENDPOINT="$CHAT_TRANSLATION_ENDPOINT"
export SPVP_CHAT_TRANSLATION_API_KEY="$CHAT_TRANSLATION_API_KEY"
export SPVP_CHAT_TRANSLATION_REGION="$CHAT_TRANSLATION_REGION"
export SPVP_CHAT_TRANSLATION_DEBUG="$CHAT_TRANSLATION_DEBUG"
export SPVP_REDIS_URL="$(build_redis_url)"

PAPER_TEMPLATE_VARS='$SPVP_SERVER_ID$SPVP_SERVER_ROLE$SPVP_PRESET_ID$SPVP_PRESET_ROOT$SPVP_REDIS_HOST$SPVP_REDIS_PORT$SPVP_REDIS_PASSWORD$SPVP_POSTGRES_HOST$SPVP_POSTGRES_PORT$SPVP_POSTGRES_DATABASE$SPVP_POSTGRES_USERNAME$SPVP_POSTGRES_PASSWORD$SPVP_IN_GAME_PLUGIN_SECRET$SPVP_REDIS_URL$SPVP_WEB_BACKEND_URL$SPVP_CHAT_MODERATION_ENABLED$SPVP_CHAT_MODERATION_ENDPOINT$SPVP_CHAT_MODERATION_API_KEY$SPVP_CHAT_TRANSLATION_ENABLED$SPVP_CHAT_TRANSLATION_ENDPOINT$SPVP_CHAT_TRANSLATION_API_KEY$SPVP_CHAT_TRANSLATION_REGION$SPVP_CHAT_TRANSLATION_DEBUG'

AIKAR_FLAGS="-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true"

cleanup_console() {
  if [ "${CONSOLE_OPEN:-0}" -eq 1 ]; then
    exec 3>&-
    exec 3<&-
    CONSOLE_OPEN=0
  fi
  rm -f "$CONSOLE_PIPE" 2>/dev/null || true
}

graceful_shutdown() {
  if [ "${CONSOLE_OPEN:-0}" -eq 1 ]; then
    printf 'save-all flush\n' >&3 || true
    printf 'stop\n' >&3 || true
    remaining=60
    while [ "$remaining" -gt 0 ] && kill -0 "$JAVA_PID" 2>/dev/null; do
      sleep 1
      remaining=$((remaining - 1))
    done
  fi
}

shutdown() {
  if [ -n "${JAVA_PID:-}" ] && kill -0 "$JAVA_PID" 2>/dev/null; then
    graceful_shutdown
    if kill -0 "$JAVA_PID" 2>/dev/null; then
      kill -TERM "$JAVA_PID"
      wait "$JAVA_PID" || true
    fi
  fi
  cleanup_console
  exit 0
}

trap shutdown TERM INT

mkdir -p /data/runtime
render_server_templates "$TEMPLATE_ROOT" "/data/runtime" "$PAPER_TEMPLATE_VARS"
install_floodgate_key "/data/runtime/plugins/floodgate"

cd /data/runtime

if [ -f /data/runtime/server.properties ]; then
  sed -i 's/^server-port=.*/server-port=25565/' /data/runtime/server.properties || true
fi

if [ -d "/data/runtime/plugins/SkyPvPWebAuth" ] && [ ! -d "/data/runtime/plugins/SkyPvPWebPlugin" ]; then
  mv "/data/runtime/plugins/SkyPvPWebAuth" "/data/runtime/plugins/SkyPvPWebPlugin" || true
fi

WORLD_DIR="/data/runtime/world"
if [ -n "$PRESET_ID" ] && [ -d "${PRESET_ROOT}/${PRESET_ID}/world" ]; then
  has_regions=0
  if [ -d "$WORLD_DIR/region" ] && ls "$WORLD_DIR/region"/*.mca >/dev/null 2>&1; then
    has_regions=1
  fi
  if [ "$has_regions" -eq 0 ]; then
    echo "[entrypoint] Seeding world from preset '${PRESET_ID}'"
    mkdir -p "$WORLD_DIR"
    for f in level.dat paper-world.yml uid.dat; do
      if [ -f "${PRESET_ROOT}/${PRESET_ID}/world/${f}" ]; then
        cp -a "${PRESET_ROOT}/${PRESET_ID}/world/${f}" "$WORLD_DIR/"
      fi
    done
    for d in data region entities poi datapacks; do
      if [ -d "${PRESET_ROOT}/${PRESET_ID}/world/${d}" ]; then
        rm -rf "$WORLD_DIR/${d}"
        cp -a "${PRESET_ROOT}/${PRESET_ID}/world/${d}" "$WORLD_DIR/"
      fi
    done
  fi
fi

cleanup_console
mkfifo "$CONSOLE_PIPE"
exec 3<>"$CONSOLE_PIPE"
CONSOLE_OPEN=1

SERVER_JAR="paper.jar"
FOLIA_JAVA_FLAGS=""
if [ "${SPVP_USE_FOLIA:-false}" = "true" ]; then
  if [ -f /data/runtime/folia.jar ] && [ "$(wc -c < /data/runtime/folia.jar)" -gt 1000000 ]; then
    SERVER_JAR="folia.jar"
    FOLIA_JAVA_FLAGS="-Dpaper.preferSparkPlugin=true"
    echo "[entrypoint] Using Folia runtime (folia.jar)"
  else
    echo "[entrypoint] WARN: SPVP_USE_FOLIA=true but folia.jar is missing or invalid — falling back to paper.jar"
  fi
fi

java -Xms${JAVA_MIN} -Xmx${JAVA_MAX} ${AIKAR_FLAGS} ${FOLIA_JAVA_FLAGS} -jar "$SERVER_JAR" --nogui <&3 &
JAVA_PID=$!
if wait "$JAVA_PID"; then
  JAVA_EXIT=0
else
  JAVA_EXIT=$?
fi

cleanup_console
exit "$JAVA_EXIT"
