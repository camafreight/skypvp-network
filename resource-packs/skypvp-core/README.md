# SkyPvP Core Resource Pack



Forced network pack offered by **Velocity**. Download hosting is **skypvp-web** (Cloudflare HTTPS).

WeaponMechanics pack send is disabled; Paper backends do not force-offer the pack.



## Contents



- WeaponMechanics v3 models, textures, and custom shoot/reload/whiz sounds (merged)

- `skypvp:laser_beam` — ItemDisplay bolt; emissive via vanilla model `light_emission` (no entity glow)

- `skypvp:laser_carbine` + FEATHER `custom_model_data` **18** / **1018** / **2018** — Laser Carbine (hip / scope / sprint)

- Guns use **FEATHER** in inventory; viewers see a packet-spoofed charged CROSSBOW (same CMD) for the loaded-hold pose
- `crossbow.json` mirrors `feather.json` so spoofed hands keep gun models
- Modern 1.21.4+ item model definitions (`assets/*/items/*.json`) including mirrored `crossbow.json`
- `skypvp:play_block_01` / `skypvp:play_block_02` — lobby play signage cubes (`play_01` / `play_02` north faces). In-world: `light[level=14]` and `light[level=15]` (resource-pack remapped). Staff: `/playblocks give <01|02|both>` on lobby (paper-core). Vanilla: `/give @s light[level=14]` or `/give @s iron_bars[minecraft:item_model="skypvp:play_block_01"]`.



## Hosting



Minecraft always downloads packs via HTTP(S) URL. The zip is served from:



`https://skypvp.gg/pack/skypvp-core.zip`



Proxy env:



- `SPVP_RESOURCE_PACK_URL=https://skypvp.gg/pack/skypvp-core.zip`

- `SPVP_RESOURCE_PACK_SHA1=<sha1 of that zip>`

- `SPVP_RESOURCE_PACK_SERVE_LOCALLY=false`



After changing pack assets:



```powershell
./infra/scripts/Resolve-SkyPvPResourcePackDuplicates.ps1  # drop stale duplicates first
./infra/scripts/Merge-WeaponMechanicsPack.ps1   # only when refreshing WM upstream
./infra/scripts/Sync-SkyPvPResourcePack.ps1
```



Then redeploy **skypvp-web** and update the proxy SHA1 env (`?h=<sha1>` query busts Cloudflare cache).



## Merge notes



`Merge-WeaponMechanicsPack.ps1` copies WM `models/`, `textures/`, `sounds/`, and `sounds.json`, then rebuilds

`assets/minecraft/items/feather.json` from upstream plus SkyPvP laser-carbine thresholds, then mirrors the same file to `crossbow.json` (viewer pose spoof uses CROSSBOW + same CMD).

Do not overwrite `assets/skypvp/` when merging.


