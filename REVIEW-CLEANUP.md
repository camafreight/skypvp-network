# Review / cleanup backlog

Items listed here are **gitignored** or already removed. Review before deleting from disk.

## Deleted (contained secrets or disposable deploy junk)

These were removed from disk because they contained credentials or were disposable deploy artifacts:

- `import-images-node1.sh`, `import-images-node2.sh` (leftover copies — `deploy-unique-run.ps1` regenerates these each run)
- `import-hotfix-node1.sh`, `import-hotfix-node2.sh`, `import-extraction-fix.sh`
- Root `*.tar` / `*.tar.gz` (image and volume exports)
- `deploy-*.log`, `node-1-journal.log`, `node1_investigation.log`
- `Test.class`, `plugin.tmp`
- `bytecode.txt`, `limbo-protocol.txt`
- `patch*.json` (one-off `kubectl patch` payloads)

## Security — rotate after public push

The initial GitHub push briefly exposed cluster credentials in git history:

| Secret | Was in | Action |
|--------|--------|--------|
| **K3s join token** | `infra/install_k3s.sh`, `infra/install_agent.sh` | **Rotate** on the control plane (`k3s token rotate`), update `infra/k3s-install.env` locally |
| **Node SSH/sudo password** | `deploy-unique-run.ps1` | **Change** on node-1/node-2, set `infra/deploy.local.env` |

Scripts now read from gitignored `infra/k3s-install.env` and `infra/deploy.local.env` (see `*.example` templates). GitHub may still flag the old token in commit history until you rotate it and optionally purge history with `git filter-repo` + force push.

## Keep — canonical K8s deploy workflow

| Path | Role |
|------|------|
| **`deploy-unique-run.ps1`** | **Primary live deploy**: `gradlew deployJars` → build Docker images from `.tmp-*.Dockerfile` → `docker save` → import to node-1/node-2 → `kubectl rollout` |
| `.tmp-paper-lobby-1.Dockerfile` | Lobby image build spec (referenced by `game-modes.json` → `lobby`) |
| `.tmp-paper-extraction-1.Dockerfile` | Extraction/Folia image build spec |
| `.tmp-proxy-local.Dockerfile` | Velocity proxy image build spec |
| `infra/k8s/scripts/Apply-GameModes.ps1` | Apply manifests (run when cluster topology changes, not every code deploy) |
| `infra/k8s/scripts/Scale-GameMode.ps1` | Scale replicas |
| `infra/k8s/scripts/Retire-LegacyGameModes.ps1` | Remove retired StatefulSets |
| `infra/k8s/scripts/Sync-NetworkCredentials.ps1` | Sync secrets from skypvp-web namespace |

Typical code/config deploy:

```powershell
cd E:\Minecraft\skypvp-network
.\deploy-unique-run.ps1
```

First-time or structural cluster changes also need `Apply-GameModes.ps1` (see `README.md`).

## Gitignored deploy outputs (regenerated each run)

| Path | Notes |
|------|-------|
| `game-images-unique.tar` | Written by `deploy-unique-run.ps1` during `docker save` |
| `import-*.sh` | Regenerated at deploy time (contains node SSH credentials — never commit) |

## Gitignored directories — safe to delete after review

Large temp trees from jar/plugin inspection. Canonical configs live under `config/server-templates/`.

| Path | ~Items | Notes |
|------|--------|-------|
| `.tmp-jar-inspect/` | 14k+ | JAR decompile/inspect scratch |
| `.tmp-geyser-strings/` | 4.5k+ | Geyser string extraction |
| `temp-geyser/` | 9.5k+ | Geyser temp unpack |
| `unjar/` | 288 | Generic jar unpack |
| `temp_unjar/`, `temp_unjar2/` | small | Jar unpack scratch |
| `recovered-jars/` | 1 | Recovered jar stash |
| `me/`, `META-INF/`, `net/`, `org/`, `network/` | small | Root-level jar class debris |

Suggested one-liner after review:

```powershell
Remove-Item -Recurse -Force .tmp-geyser-strings, .tmp-jar-inspect, temp-geyser, temp_unjar, temp_unjar2, unjar, recovered-jars, me, META-INF, net, org, network -ErrorAction SilentlyContinue
```

## Gitignored abandoned / duplicate trees

| Path | Reason |
|------|--------|
| `web-admin/` | Abandoned Next.js stub (4 files, no `package.json`) — superseded by `skypvp-web` |
| `WeaponMechanics/` (repo root) | Duplicate of `config/server-templates/extraction/plugins/WeaponMechanics/` |
| `paper-plugin.yml` (repo root) | Extracted plugin descriptor, not part of build |
| `RUN-NETWORK.cmd` | Broken — references missing `scripts/deploy-full-network-local.ps1` |

## Keep (canonical infra)

| Path | Notes |
|------|-------|
| `infra/docker/.env.example` | Template only |
| `infra/docker/.env` | **Local secrets — gitignored** |
| `infra/scripts/`, `infra/k8s/scripts/` | Supported deploy/maintenance scripts |
| `gradlew`, `gradlew.bat`, `gradle/` | Required for builds |
