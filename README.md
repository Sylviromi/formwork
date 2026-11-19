# Formwork

Place whole multiblocks in one go, from your inventory or your ME network. Pick a structure, set its size, tier and parts, check what it costs, then place it. Placement is all or nothing. You can also edit or take down what you placed.

Forge 1.20.1. AE2 is optional.

## Features

- **Picker.** Search every structure the server knows, grouped by mod and tier, with a 3D preview you can turn, pan, zoom and slice layer by layer.
- **Sizable Mekanism multiblocks.** The fission reactor, industrial turbine, thermoelectric boiler, induction matrix, dynamic tank and thermal evaporation plant are generated from settings such as width, height, rotors, coils and glass, following Mekanism's own formation rules.
- **Choose every part.** Click a block in the preview to choose what goes there: which port where, glass or casing on a wall, void miner modifiers, Masterful Machinery port sizes (for packs that use the usual tiny to gigantic port names). Frame edges stay casing, so the machine still forms.
- **Materials first.** The Materials tab shows what you need and what you have in your inventory and on the ME network. Nothing is used unless everything is there.
- **AE2.** Put the tool in a Wireless Access Point to link it. Missing blocks with a pattern can be crafted from the Materials tab, one at a time or all at once, and the running crafts show their progress.
- **Placement card (H).** Anchor the preview, move it block by block, turn it. Hold Ctrl when placing to skip spots that are in the way.
- **Edit in place.** Sneak and use the tool on a multiblock you placed to change its size, tier or parts. Only the blocks that change are swapped; you pay for the new ones and get the old ones back.
- **Dismantle.** Hold use on a multiblock you placed. Its blocks go to the ME network, then your inventory.
- **JEI.** Every structure is a recipe listing its materials, with a spinning preview: U on a block shows the structures that use it, R on the tool shows them all.
- Respects claims and protection: every block is checked as if you placed or broke it yourself.

Optional integrations: JEI, Mekanism and Mekanism Generators, More Thermal Evaporation (evaporation tiers), Mekanism Extras (absolute to infinite induction tiers), Void Miners, Masterful Machinery, NuclearCraft, ExtendedAE. Anything that is not installed is simply left out.

## Crafting

```
 E      E: ender pearl
IRI     I: iron ingot
 I      R: block of redstone
```

## For pack makers

Structures and variant sets are plain datapack files, so any pack can add its own.

**Structures:** vanilla structure-block files at `data/<namespace>/formwork/structures/<path>.nbt`. Save the multiblock with a structure block and drop the file in. Files whose name ends in `_tier<N>` (for example `crusher_tier_1`, `crusher_tier_2`) become one entry with a tier selector. A structure that uses a block from a mod that is not installed is skipped. Name an entry with the lang key `formwork.structure.<file name>`; otherwise the file name is shown in readable words.

**Variant sets:** `data/<namespace>/formwork/variants/<name>.json` lists blocks that may replace each other:

```json
{
  "blocks": ["mekanism:basic_mechanical_pipe", "mekanism:advanced_mechanical_pipe"],
  "columns": 2,
  "walls": false,
  "match": "",
  "family": ""
}
```

- `blocks`: the interchangeable blocks; any not installed are dropped. Only `blocks` is required.
- `columns`: width of the choice grid in the editor (0 = fit the panel).
- `walls`: only positions on one face of the structure's bounds can be swapped (frame edges and corners keep their block). The set then only applies to structures that contain one of its own blocks.
- `match`: only apply to structures whose file name contains this text. Usually not needed: when several sets list a block, the set with most of its blocks in the structure wins.
- `family`: sets with the same family may take each other's places (all Masterful Machinery ports share one, since MM accepts ports anywhere). The editor then checks that each set keeps the blueprint's count.

**Command:** `/formwork place <entry> <pos> [settings...] [block=count...]` places an entry for free (permission level 2), which helps when testing structures.

## Building

Java 17: `./gradlew build`. The jar is written to `build/libs/`.

## Credits

Inspired by igentuman's [Multi Builder Tool](https://github.com/igentuman/multi-builder-tool).

## License

MIT, see [LICENSE.md](LICENSE.md).
