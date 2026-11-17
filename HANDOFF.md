# Multiblock Placer: handoff and todo

Read this first in a new session. It is updated as work proceeds.
Approved plan: `/home/shrimp/.claude/plans/ok-lets-plan-this-declarative-fiddle.md`.

## What this is
A fresh Forge 1.20.1 mod (`mbplacer`) that replaces igentuman's Multi Builder Tool (`mbtool`) for the pack Project Infinity 0.1.
The old mod's source is at `/mnt/Projects/multi-block-placer` and is used as reference only.
- The tool has no inventory and no energy.
- It links to AE2 through a Wireless Access Point.
- It takes blocks from the player inventory first, then ME. Placement is all-or-nothing.
- It can request AE2 crafts for missing blocks.
- It reads the pack's existing `data/*/mbtool_structures/**.nbt` and its own `data/*/mbplacer/structures/**.nbt`.
- Variant sets live in `data/*/mbplacer/variants/*.json`. They let you choose how many of each void miner modifier or Masterful Machinery port tier to use.
- Tiers are grouped by filename: `xxx_tier3_yyy` becomes group `xxx`, tier 3.
- Mekanism sizable multiblocks are generated from params in `structure/gen/`.

## Build and run
- Java 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew build`.
- First run: `./gradlew copyPackMods`. It copies void miners, mango multiblock and Masterful Machinery from the pack into `libs/` for dev runtime.
- Dev client: `./gradlew runClient`. It loads AE2, JEI and Mekanism from Maven at the pack's versions.

## Layout (src/main/java/dev/sylviromi/mbplacer)
- `MbPlacer`: registration and event wiring.
- `item/PlacerItem`: the tool. The selection is in stack nbt, and the AE link is the `accessPoint` tag.
- `structure/`:
  - `BlockSpec`, `Blueprint`: NBT parsing.
  - `Selection`: entry, params and variant counts.
  - `VariantSet`.
  - `Catalog`: entries, groups and generators. Has server and client instances.
  - `CatalogLoader`: reload listener.
- `structure/gen/`: `Generator`, `Param`, `Box`, and the generators FissionReactor, IndustrialTurbine, ThermoelectricBoiler, InductionMatrix and DynamicTank.
- `build/`:
  - `Resolver`: selection to specs to rotated world blocks.
  - `Materials`: needed vs inventory/ME, take.
  - `Placer`: server placement.
- `ae2/AeLink`: the only class touching AE2. It handles link, range check, snapshot, all-or-nothing take, and craft requests ticked on the server.
- `net/Net`: packets for catalog sync, SetSelection, Report and RequestCrafts. `ClientData` holds the last report.
- `client/`: `ClientHooks`, plus `PlacerScreen` and the world ghost, both TODO.

## Status (updated 2026-11-17)
- [x] Gradle build, 12 JUnit tests pass (`./gradlew test`).
- [x] Structure layer, generators, resolver, materials, placer, AE2 link, network, item, resources.
- [x] `PlacerScreen`:
  - searchable grouped list
  - 3D preview: drag to rotate, scroll to zoom
  - generator settings, variant counts
  - materials coloured green/yellow/red with tooltips
  - Craft missing button
  - changes sent to the server live
- [x] `WorldGhost`: translucent ghost in front of the player, red where blocked, outline box.
- [x] Op command `/mbplacer place <entry> <pos> [params...] [block=count...]` places for free. Useful for pack makers and headless tests.
- [x] Catalog sync uses a compact palette encoding. Raw NBT broke joining: the 18x18x18 reactor exceeded the NBT packet limit, and `neoeco_f9_crafting_controller` is 1.5 MB.
- [x] Verified on the headless dev server (Mekanism's own formation marker, `inventoryID` on the casing):
  - fission 3x4x3 and 18x18x18
  - turbine 5x9x5 and 11x18x11 with 14 rotors, coils and condensers
  - boiler, induction matrix, dynamic tank, thermal evaporation
- [x] Turbine rules read from Mekanism's decompiled `TurbineValidator`:
  - coils must be face-connected from above the complex
  - rotors are limited by width
- [x] Evaporation rules taken from `EvaporationValidator`.
- [x] Verified with screenshots (`./gradlew runClient -Pmbdev`, output in `run/screenshots/`): picker for void miner, fission and turbine, and the world ghost.
- [ ] **Void miner formation is not verified.** It places correctly with mixed modifiers, but the formed flag is only in memory and the controller needs power to show work.
  - Test in game: build a tier 9 with mixed modifiers and right-click the controller.
  - Also unknown: whether a tier N miner accepts higher or lower tier modifiers.
- [ ] **AE2 is untested in game.** Test:
  - link in a WAP
  - materials show ME counts
  - placement takes from ME
  - Craft missing starts jobs
- [ ] **Masterful Machinery port variants are untested.** MM port blocks only exist with the pack's MM configs, so test in the pack.
- [ ] Pack integration:
  - copy `build/libs/mbplacer-1.20.1-0.1.0.jar` to the pack's mods folder and remove `MultiBuilderTool-1.20.1-1.0.28.jar`
  - update `kubejs/client_scripts/tooltips.js` (around line 281) and `kubejs/server_scripts/scripts/world_interaction_scripts/creative_bin_blacklist.js` (line 182), which reference `mbtool:mbtool`
  - update the quests that mention mbtool (`config/ftbquests/.../tips_and_tricks.snbt`)
  - optionally add `mbplacer.structure.<file>` or `mbplacer.group` names. Existing `mbtool.structure.*` lang keys are reused.
- [ ] Nice to have:
  - mesh cache for the preview if large structures lag
  - manual rotation key
  - nicer icons in the list
  - JEI integration
- [ ] Later, as a separate task: a learning scaffold so the user can rewrite the mod themselves.

## How to test quickly
- Headless: `./gradlew runServer` (port 25599, RCON 25575 with password test), then `python3 /tmp/rcon.py "mbplacer place mbplacer:gen/mekanism_turbine 80 -60 0"` and `data get block ...`. The rcon.py script is a 10-line client and may need recreating.
- Screenshots: `./gradlew runClient -Pmbdev` uses `run/saves/test` (a copy of `run/world` with the pack structures datapack). See `client/DevHooks`.

## Decisions (from the user)
- Fresh mod with a new id. Keep reading the mbtool NBT data.
- ME link through a WAP only, no wireless terminal. No energy or XP cost.
- v1 covers void miners and solar, Mekanism sizable multiblocks, all pack NBTs, and Masterful Machinery port tiers.
- Out of v1: dismantle, JEI category, KubeJS event, GregTech, NuclearCraft import, Curios, undo.
- Variant sets can carry `"match": "<substring of the structure file name>"`. It is used because the null modifier is in both the void miner and solar sets.
- Variant choice is by count per set ("4 speed, 4 energy, rest null") instead of clicking single positions. This deviates from the plan and is simpler UI.
- Rotation follows the player's facing. The structure starts at the clicked block and extends away from the player, centred sideways. There is no manual rotate key.
- Turbine rotors are placed without blades. The player inserts the blades.

## Known risks
- Mekanism rules are unverified in game.
- The preview renders blocks every frame with no mesh cache. If big structures lag, add a VertexBuffer cache, porting `client/render/StructureMesh.java` from the old repo.
