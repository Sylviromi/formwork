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

## Status
- [x] Gradle skeleton builds (`build/libs/mbplacer-1.20.1-0.1.0.jar`).
- [x] Structure layer, generators, resolver, materials, placer, AE2 link, network, item.
- [ ] First full compile of everything above. This was in progress when the file was written.
- [ ] Client: PlacerScreen with a searchable grouped list, 3D preview, params, variant counts, material list and a request crafts button.
- [ ] Client: in-world ghost preview at the target, tinted red where blocked.
- [ ] Resources:
  - lang `en_us.json` (msg/tooltip keys used in code: `mbplacer.msg.*`, `mbplacer.tooltip.*`)
  - item model and texture
  - recipe
  - default variant sets: void miner modifiers, solar modifiers, Masterful Machinery ports, Mekanism pipes. Port them from the pack's `config/mbtool-common.toml` and fix its two bugs:
    - the MM fluid-output set ends with `mm:tiny_energy_port_input`
    - the NuclearCraft entry has a space after its comma
- [ ] JUnit tests:
  - group parsing on pack filenames
  - generator shell counts
  - Resolver rejecting a block outside a variant set
- [ ] Dev run:
  - Tier 9 void miner with mixed modifiers forms.
  - Fission at 3x4x3 and 18x18x18, a turbine and a boiler form in Mekanism.
- [ ] Verify Mekanism rules in game. Turbine min size, coil and vent placement and the boiler disperser rule were written from memory of Mekanism 10.4.
- [ ] Thermal evaporation generator. Not written yet. Check the controller position rule in Mekanism's EvaporationValidator first.
- [ ] Pack integration:
  - put the jar in the pack and remove `MultiBuilderTool`
  - update `kubejs/client_scripts/tooltips.js` (around line 281) and `kubejs/server_scripts/.../creative_bin_blacklist.js` (line 182), which reference `mbtool:mbtool`
  - update the quests that mention mbtool
- [ ] Later, as a separate task: a learning scaffold so the user can rewrite the mod themselves.

## Decisions (from the user)
- Fresh mod with a new id. Keep reading the mbtool NBT data.
- ME link through a WAP only, no wireless terminal. No energy or XP cost.
- v1 covers void miners and solar, Mekanism sizable multiblocks, all pack NBTs, and Masterful Machinery port tiers.
- Out of v1: dismantle, JEI category, KubeJS event, GregTech, NuclearCraft import, Curios, undo.
- Variant choice is by count per set ("4 speed, 4 energy, rest null") instead of clicking single positions. This deviates from the plan and is simpler UI.
- Rotation follows the player's facing. There is no manual rotate key.
- Turbine rotors are placed without blades. The player inserts the blades.

## Known risks
- Mekanism rules are unverified in game.
- The preview renders blocks every frame with no mesh cache. If big structures lag, add a VertexBuffer cache, porting `client/render/StructureMesh.java` from the old repo.
