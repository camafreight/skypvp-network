# Game modes (config-driven)

Active Minecraft modes are defined in **`game-modes.json`**, not hardcoded in scripts.

## Current modes

| Mode | StatefulSet | Node | Purpose |
|------|-------------|------|---------|
| **lobby** | `skypvp-lobby` | `node-1` | Big dedicated hub lobbies (main gamemode features) |
| **extraction** | `skypvp-extraction` | `skypvp-node-2` | Horizontally scaled extraction match instances |

Retired: `minigame`, `survival` (removed from cluster; definitions live under `retired` in config).

## Files

```
infra/k8s/
  game-modes.json       # canonical mode list (edit this to add/change modes)
  game-servers.yaml     # lobby + extraction StatefulSets/Services
  scripts/
    Apply-GameModes.ps1
    Retire-LegacyGameModes.ps1
    Scale-GameMode.ps1
    _game-mode-helpers.ps1
```

## Apply to cluster

```powershell
cd E:\Minecraft\skypvp-network
.\infra\k8s\scripts\Retire-LegacyGameModes.ps1
.\infra\k8s\scripts\Apply-GameModes.ps1
```

Or from skypvp-web cluster migration:

```powershell
cd E:\Minecraft\skypvp-web
.\k8s\cluster\scripts\06-apply-game-modes.ps1
```

## Scale extraction replicas

```powershell
.\infra\k8s\scripts\Scale-GameMode.ps1 -ModeId extraction -Replicas 4
```

Scale lobby (multiple big hub instances on node-1):

```powershell
.\infra\k8s\scripts\Scale-GameMode.ps1 -ModeId lobby -Replicas 2
```

## Add a new mode

1. Add an entry to `game-modes.json` under `modes`.
2. Add a StatefulSet + headless Service block to `game-servers.yaml` (or generate from template).
3. Add a world preset under `config/world-templates/<presetId>/`.
4. Add plugin configs under `config/server-templates/<mode>/` (see `config/server-templates/README.md`).
5. Build a Paper image tag referenced by the mode's `image` field.
5. Run `Apply-GameModes.ps1`.

## Extraction image

Build and deploy with:

```powershell
cd E:\Minecraft\skypvp-network
.\gradlew deployJars
.\deploy-unique-run.ps1
```

This builds `ghcr.io/skypvp/paper-core:extraction-local` from `.tmp-paper-extraction-1.Dockerfile` and imports it on **skypvp-node-2**. The `servers:paper-extraction` module provides the mode plugin jar staged into `config/server-templates/extraction/plugins/`.
