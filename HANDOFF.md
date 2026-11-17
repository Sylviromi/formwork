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
- `build/Dismantler`: removes the selected structure's blocks at the placement and returns drops (ME, then inventory, then floor).
- `client/`:
  - `ClientHooks`: key bindings (G anchor, H placement panel) and placement edits.
  - `PlacerScreen`: the picker.
  - `PlacementScreen`: see-through panel for anchor, offset, rotation and mode.
  - `StructureMesh`: one VertexBuffer per chunk layer, built once per change. Used by the preview and the ghost.
  - `WorldGhost`.
- Placement settings live in the tool nbt (`anchor`, `anchorFacing`, `offset`, `rotation`, `dismantle`). `PlacerItem.placement()` turns them into target + facing on both sides. The server clamps offsets to 64 and refuses anchors or targets more than 128 blocks away.

## Status (updated 2026-11-17, v0.2.0)
- [x] v0.2.0:
  - preview depth fixed (the Z scale was negative, so back faces drew)
  - preview fits its panel and the layout scales with the screen
  - preview and ghost use a cached mesh (no per-frame tessellation)
  - anchor/offset/rotate panel
  - dismantle mode
  - turbine rotors placed with 2 blades each, and blades count as materials (verified headless: rotors keep `blades: 2`, turbine forms)
- [ ] **Dismantle, anchor and placement panel are untested in game** (only screenshots of the panel). Test: anchor with G, nudge with H, place; switch to dismantle and remove it.
- [ ] Preview does not draw block entity renderers, so turbine blades show only in the world, not in the picker.
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
  - Resolved: the modifier slot accepts the `voidminers:modifiers` tag, which holds every tier plus null and solar modifiers, so mixed tiers are valid.
  - Frames use `frames/N` tags: tier N accepts frames of tier N and up. Not offered as a variant yet.
- [ ] **AE2 is untested in game.** Test:
  - link in a WAP
  - materials show ME counts
  - placement takes from ME
  - Craft missing starts jobs
- [ ] **Masterful Machinery port variants are untested.** MM port blocks only exist with the pack's MM configs, so test in the pack.
- [x] Pack integration (pack: `/data/games/PrismLauncher/instances/Project Infinity 0.1/minecraft`). Backups of every edited file: `../mbplacer-migration-backup.tar.gz` and `../mbplacer-migration-backup-lang.tar.gz` (both in the instance folder).
  - `mods/mbplacer-1.20.1-0.2.0.jar` added; old jar renamed to `MultiBuilderTool-1.20.1-1.0.28.jar.disabled`
  - the 73 structures that only shipped inside the old jar were copied to `kubejs/data/mbtool/mbtool_structures/`, and their names merged into `kubejs/assets/mbtool/lang/*.json`
  - tooltips.js, creative_bin_blacklist.js (entry removed, the tool has no inventory), guide `multibuilder_tool.md`, the tips_and_tricks quest reward, and the quest text now point at `mbplacer:placer`
  - es_es/ru_ru `tooltip.mbtool.usage_2` still describe the old shift+scroll rotation
- [ ] Nice to have:
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
- Out of v1: JEI category, KubeJS event, GregTech, NuclearCraft import, Curios, undo.
- Variant sets can carry `"match": "<substring of the structure file name>"`. It is used because the null modifier is in both the void miner and solar sets.
- Variant choice is by count per set ("4 speed, 4 energy, rest null") instead of clicking single positions. This deviates from the plan and is simpler UI.
- Rotation follows the player's facing. The structure starts at the clicked block and extends away from the player, centred sideways. The placement panel adds rotation and offset; anchoring folds them into the anchor.
- Dismantle only removes blocks whose block matches the blueprint at that position; everything else is left alone. Creative players get no drops.
- Turbine rotors are placed with 2 blades each (Mekanism sums rotor `blades` when the turbine forms). Blades are charged as materials.

## Known risks
- Mekanism rules are unverified in game.
