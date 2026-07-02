# Server templates (canonical server trees)
#
# Each preset is a complete server directory rendered into /data/runtime at
# container start by infra/scripts/*-entrypoint.sh.
#
# Layout per preset (proxy | lobby | extraction):
#   paper.jar / folia.jar / velocity.jar   server binary (extraction uses folia.jar when SPVP_USE_FOLIA=true)
#   server.properties, *.yml      root configs
#   config/                       Paper global config
#   plugins/
#     *.jar                       third-party + SkyPvP plugin jars
#     <plugin>/config.yml         templated configs (${SPVP_*})
#
# Worlds are NOT here — see config/world-templates/.
# Floodgate key.pem is mounted separately at /opt/skypvp/floodgate-key/.
#
# LuckPerms owns permission data in PostgreSQL database `skypvp_network`
# (tables luckperms_*). Legacy SQL tables network_ranks / network_player_ranks
# are unused — manage ranks with /lp or infra/scripts/Seed-LuckPerms.ps1.
#
# Placeholders (envsubst on text files only; jars copied as-is):
#   ${SPVP_SERVER_ID} ${SPVP_SERVER_ROLE} ${SPVP_PRESET_ID} ${SPVP_PRESET_ROOT}
#   ${SPVP_REDIS_HOST} ${SPVP_REDIS_PORT} ${SPVP_REDIS_PASSWORD} ${SPVP_REDIS_URL}
#   ${SPVP_POSTGRES_HOST} ${SPVP_POSTGRES_PORT} ${SPVP_POSTGRES_PASSWORD}
#   ${SPVP_IN_GAME_PLUGIN_SECRET} ${SPVP_WEB_BACKEND_URL} ${SPVP_PROXY_ID}
#   ${SPVP_CHAT_MODERATION_ENABLED} ${SPVP_CHAT_MODERATION_ENDPOINT} ${SPVP_CHAT_MODERATION_API_KEY}
#   ${SPVP_TCPSHIELD_ONLY_ALLOW_PROXY} ${SPVP_TCPSHIELD_GEYSER_SUPPORT}
#   ${SPVP_GEYSER_BEDROCK_PORT} ${SPVP_GEYSER_MOTD1} ${SPVP_GEYSER_MOTD2} ${SPVP_GEYSER_SERVER_NAME}
#
# Staging jars:
#   ./gradlew deployJars              SkyPvP modules → plugins/
#   ./gradlew stageBedrockPlugins     Geyser/Floodgate → plugins/
#   ./gradlew stageFoliaJar           Folia server jar → extraction template root
#
# WeaponMechanics and other plugin YAML: edit under config/server-templates/<mode>/plugins/.
# At container start, infra/scripts/lib/render-server-templates.sh copies the whole tree
# into /data/runtime and runs envsubst on text files (${SPVP_*} placeholders).
#
# Seed baseline groups/permissions (local dev, skypvp-web luckperms container):
#   .\infra\scripts\Seed-LuckPerms.ps1
#
# Chat moderation (Azure) — skypvp-network only (not skypvp-web/.env):
#   Local Docker: copy infra/docker/.env.example → infra/docker/.env, then restart compose.
#   Kubernetes: add CHAT_MODERATION_* to skypvp-network namespace secret skypvp-network-env
#   (optionally seed from skypvp-web-env via Sync-NetworkCredentials.ps1), then roll game pods.
#
# Note: Velocity proxy templates use plugins/luckperms/ (lowercase); Paper uses plugins/LuckPerms/.
#
# Rebuild K8s images: .\deploy-unique-run.ps1
