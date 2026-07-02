# SkyPvP Network



Java Minecraft network: Velocity proxy, Paper game servers (lobby + extraction), Redis, and PostgreSQL.



## Repository layout



- `shared/network-core` — shared models and contracts

- `proxy/velocity-core` — Velocity plugin

- `servers/paper-core` — Paper core plugin

- `servers/paper-lobby` — lobby mode plugin

- `servers/paper-extraction` — extraction mode plugin

- `config/server-templates` — canonical server trees (jars + configs) rendered at pod start

- `config/world-templates` — lobby and extraction world presets

- `infra/docker` — local Docker Compose stack (lobby + extraction + proxy)

- `infra/k8s` — live cluster manifests and deploy scripts



## Build plugin jars



```powershell

cd E:\Minecraft\skypvp-network

.\infra\scripts\Generate-FloodgateKey.ps1   # once per environment

.\gradlew deployJars

```



This compiles SkyPvP modules and stages jars into `config/server-templates/*/plugins/`.



Requires `skypvp-web-plugin` built at `E:\Minecraft\skypvp-web\skypvp-web-plugin\build\libs\skypvp-web-plugin.jar`.



## Bedrock crossplay (Geyser + Floodgate)



- **Java clients** connect to the Velocity proxy on TCP `25565`.

- **Bedrock clients** connect to Geyser on UDP `19132`.

- Floodgate authenticates Bedrock players; the shared `config/floodgate/key.pem` must match on proxy and all Paper backends.



## Deploy to live K3s cluster



```powershell

.\infra\k8s\scripts\Retire-LegacyGameModes.ps1

.\infra\k8s\scripts\Apply-GameModes.ps1

.\deploy-unique-run.ps1

```



See `infra/k8s/README.md` for scaling and adding modes.



## Local Docker Compose



```powershell

.\gradlew deployJars

docker compose -f infra/docker/compose.yaml up -d

```



Join at `localhost:25565` (Java) or `localhost:19132` (Bedrock). Redis and PostgreSQL come from the separate `skypvp-web` stack.

