# Formwork: handoff and todo

Read this first in a new session. It is updated as work proceeds.
Approved plan: `/home/shrimp/.claude/plans/ok-lets-plan-this-declarative-fiddle.md`.

## What this is
A standalone Forge 1.20.1 mod, Formwork (id `formwork`; it was called Multiblock Placer, id `mbplacer`, until 2026-11-19). It is not a remake of igentuman's Multi Builder Tool (`mbtool`). As a temporary, clearly marked patch it still reads that mod's structure folder and names, so Project Infinity works unchanged (user, 2026-11-19). Developed and tested against the pack Project Infinity 0.1, which is not ours.
Multi Builder Tool's source is at `/mnt/Projects/multi-block-placer`, only for reading its data format; never copy code from it.
- The tool has no inventory and no energy.
- It links to AE2 through a Wireless Access Point.
- It takes blocks from the player inventory first, then ME. Placement is all-or-nothing.
- It can request AE2 crafts for missing blocks.
- It reads blueprints (vanilla structure-block `.nbt`) from `data/*/formwork/structures/**.nbt`, and for now `data/*/mbtool_structures/**.nbt` (`CatalogLoader.LEGACY`, patch-work, to be removed).
- Variant sets live in `data/*/formwork/variants/*.json`. They let you choose how many of each void miner modifier or Masterful Machinery port tier to use.
- Tiers are grouped by filename: `xxx_tier3_yyy` becomes group `xxx`, tier 3.
- Mekanism sizable multiblocks are generated from params in `structure/gen/`.

## Build and run
- Java 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew build`.
- First run: `./gradlew copyPackMods`. It copies void miners, mango multiblock and Masterful Machinery from the pack into `libs/` for dev runtime.
- Dev client: `./gradlew runClient`. It loads AE2, JEI and Mekanism from Maven at the pack's versions.

## Layout (src/main/java/dev/sylviromi/formwork)
- `Formwork`: registration and event wiring.
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

## Status (updated 2026-11-17, v0.3.0)
- [x] v0.3.0 (installed in the pack):
  - GUI split: `BrowserScreen` (search, mod categories, cards with lazy 3D thumbnails) and `EditorScreen` (3D view with turn/reset/spin/pan and a layer slider; Settings / Variants / Materials tabs)
  - variants are icon grids with count badges (click +1, right-click -1, shift fills/clears). `"columns"` in a variant JSON sets the grid width (void miner 3, solar 2)
  - placement card (H): anchor toggle, N/E/S/W compass, look-relative pad, up/down, reset, Place/Dismantle
  - `client/Ui`: palette, flat buttons with hover, 9x9 pixel icons (`textures/gui/icons.png`), the shared 3D view. Clears depth after the model so GUI stays on top
  - `build/Placements`: SavedData of every placement per dimension, synced to clients. Pointing at one with the tool highlights it (`WorldGhost.renderPlaced`, `Hud`)
  - dismantle: hold use on a placed multiblock, 1-5 s by size, cancels when looking away. No ghost-alignment fallback (user decision)
  - edit placed: sneak+use on it opens the editor in edit mode; `build/Updater` swaps only differing blocks, charges the new ones, returns the old ones
  - verified in the dev run (`-Pmbdev`): MM water condenser placed with a tiny port variant, edited 1 -> 2 tiny ports, held-use dismantle left 0 blocks and 0 records
- Dev MM test setup: `./gradlew copyPackMods` (now also KubeJS, Rhino, Architectury), the pack's `kubejs/startup_scripts/masterfull_machinery` in `run/kubejs/startup_scripts`, then `python3 tools/dev_mm_structures.py "<pack>/minecraft"`.
- [x] v0.2.0:
  - preview depth fixed (the Z scale was negative, so back faces drew)
  - preview fits its panel and the layout scales with the screen
  - preview and ghost use a cached mesh (no per-frame tessellation)
  - anchor/offset/rotate panel
  - dismantle mode
  - turbine rotors placed with 2 blades each, and blades count as materials (verified headless: rotors keep `blades: 2`, turbine forms)
- [ ] **Untested by a real player in the pack:** anchor (G), the placement card (H), held-use dismantle and editing placed multiblocks. The dev run drives them through code and a simulated use key.
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
- [x] Op command `/formwork place <entry> <pos> [params...] [block=count...]` places for free. Useful for pack makers and headless tests.
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
- [x] Pack integration, done under the old name (the pack is not ours; ask before changing it) (pack: `/data/games/PrismLauncher/instances/Project Infinity 0.1/minecraft`). Backups of every edited file: `../mbplacer-migration-backup.tar.gz` and `../mbplacer-migration-backup-lang.tar.gz` (both in the instance folder).
  - `mods/mbplacer-1.20.1-0.2.0.jar` added; old jar renamed to `MultiBuilderTool-1.20.1-1.0.28.jar.disabled`
  - the 73 structures that only shipped inside the old jar were copied to `kubejs/data/mbtool/mbtool_structures/`, and their names merged into `kubejs/assets/mbtool/lang/*.json`
  - tooltips.js, creative_bin_blacklist.js (entry removed, the tool has no inventory), guide `multibuilder_tool.md`, the tips_and_tricks quest reward, and the quest text now point at `mbplacer:placer`
  - es_es/ru_ru `tooltip.mbtool.usage_2` still describe the old shift+scroll rotation
- [ ] Nice to have:
  - nicer icons in the list
  - JEI integration
- [ ] Later, as a separate task: a learning scaffold so the user can rewrite the mod themselves.

## How to test quickly
- Headless: `./gradlew runServer` (port 25599, RCON 25575 with password test), then `python3 /tmp/rcon.py "formwork place formwork:gen/mekanism_turbine 80 -60 0"` and `data get block ...`. The rcon.py script is a 10-line client and may need recreating.
- Screenshots: `./gradlew runClient -Pmbdev` uses `run/saves/test` (a copy of `run/world` with the pack structures datapack). See `client/DevHooks`. `-Pmbdev=browser,mirror` runs only those groups (browser, editor, mm, placement, mirror, place, update, dismantle, animation, me, craft, jei); plain `-Pmbdev` runs all.

## v0.4.0 (2026-11-17, installed in the pack)
- No tool modes: pointing at a block of a placed multiblock, hold use = dismantle, sneak+use = edit; anywhere else use = place. Only real blocks of a placement count (air or foreign blocks inside its bounds do not).
- Highlight: `client/Silhouette`, a flat-coloured shell of the outer faces (cyan, red while dismantling) plus corner brackets.
- Dismantle and in-place updates take 0.5-2 s (`Placements.dismantleTicks`). Updates run as a server job in `Updater` (checked and paid first, blocks swapped bottom-up); the client gets one `Net.Progress` with the duration and animates the bar itself.
- Per-position variants: `Selection.overrides` (blueprint pos -> block), applied after legacy counts. Grids edit overrides; clicking a block in the editor view selects it and the Variants tab shows "Selected block" with prev/next and one row per set of its family.
- Families: variant JSON `"family"` (all MM port sets: `mm_ports`). `Resolver.check` compares per-set counts with the blueprint (MM layouts use portsAnywhere); the Variants tab shows them and the tab label counts problems.
- Dev run: tier 9 void miner place -> timed edit (4 speed -> 4 item) -> hold-to-dismantle, all logged; two editor shots 2.75 s apart confirmed the animated ultimate textures cycle in the preview.
- Still open: AE2 in game; play-testing by a real player in the pack.

## Names (v0.3.1): reuse other mods' translations
- Generators name themselves with Mekanism's keys (`Generator.nameKey()`), settings with Mekanism block/GUI keys (`Param.named(...)`), toggles with vanilla `options.on/off`.
- Variant sets are named by the words their blocks' names share ("Energy Port Input"); tier groups by the words their members' names share, minus "Tier".
- Common GUI words use vanilla keys (`gui.back`, `gui.cancel`, `controls.reset`, `gui.recipebook.search_hint`, `gui.socialInteractions.tab_all`).
- A pack can still override any name with `formwork.variants.<set>`, `formwork.structure.<file>` or `formwork.param.<name>`.
- Browser category: the mod of the block whose id contains "controller", else of the rarest modded block.

## Decisions (from the user)
- New mod with its own id (now `formwork`), not a remake of Multi Builder Tool, and standalone; `mbtool_structures` and `mbtool.structure.*` names are read again only as a temporary patch for Project Infinity (user, 2026-11-19), logged at load. The dev worlds keep their test structures in the `pi` datapack under `data/pi/formwork/structures`.
- ME link through a WAP only, no wireless terminal. No energy or XP cost.
- v1 covers void miners and solar, Mekanism sizable multiblocks, all pack NBTs, and Masterful Machinery port tiers.
- Out of v1: JEI category, KubeJS event, GregTech, NuclearCraft import, Curios, undo.
- Without `match`, a block shared by several sets goes to the set with most of its blocks in the structure (content, not file names, so any pack's structures work). `"walls": true` sets (Mekanism `*_walls.json`) only swap positions on one face of the bounds, and only apply when the structure has one of the set's own blocks (listed by no other set).
- "Craft all missing" is one AE2 job (`ae2/StructureCraft`): a temporary non-world node on the linked grid offers a pattern (all missing blocks in, a `formwork:structure_materials` token with a per-job tag out) and a token sink at priority MAX-1, below AE2's CPU interceptor. The plan screen is opened directly with a plan requested through our node (planned through the screen's host, or via the amount screen, AE2 left the pattern unused). On push the blocks go back into ME and the token completes the job. Verified in the dev run: plan 146 glass + 68 casing, Start, blocks back in ME, done message.
- GUI spacing: every screen uses `Ui`'s scale (GAP 2 between controls, INSET 4 panel edge to its controls, PAD 6 around panels and to text, CTRL 15 control height, ROW 18 list rows, MARGIN 8 to the screen edge) and `Ui.textY` to centre text. Row highlights bleed PAD-INSET past the text so they line up with the tabs; the last tab takes the rounding so its right gap equals its top gap.
- Blueprints drop Mekanism's `inventoryID` on load, so placed copies do not share one cached multiblock.
- Variant sets can carry `"match": "<substring of the structure file name>"`. It is used because the null modifier is in both the void miner and solar sets.
- Variant choice is by count per set ("4 speed, 4 energy, rest null") instead of clicking single positions. This deviates from the plan and is simpler UI.
- Rotation follows the player's facing. The structure starts at the clicked block and extends away from the player, centred sideways. The placement panel adds rotation and offset; anchoring folds them into the anchor.
- Dismantle only removes blocks whose block matches the blueprint at that position; everything else is left alone. Creative players get no drops.
- Turbine rotors are placed with 2 blades each (Mekanism sums rotor `blades` when the turbine forms). Blades are charged as materials.

## Known risks
- Mekanism rules are unverified in game.
