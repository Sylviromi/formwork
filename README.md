# Formwork

Formwork places whole multiblocks for you. Pick a structure, set its size, tier and parts, look at what it costs, and place it. It either works completely or does nothing, so you never end up with half a reactor. Afterwards you can edit it or take it down again.

For Forge 1.20.1. AE2 is optional.

## What it does

**Configurable variants.** Many structures let you swap blocks for others of the same kind. Click a block in the preview to see what can go there, such as a different port, glass instead of casing, or another tier of the same part. The choices come from variant sets that packs can define, and frame edges and corners keep their block so the structure stays valid.

**Materials.** The Materials tab lists what the structure needs next to what you have, in your inventory and on your ME network. If anything is missing, nothing gets used.

**AE2.** Put the tool in a Wireless Access Point to link it. Click a missing block to craft it, or craft everything missing as one job, which you plan and confirm in AE2's own screen. Running crafts show their progress.

**Placement card (H).** Anchor the preview, nudge it block by block and turn it. Hold Ctrl when placing to skip spots that are in the way.

**Editing.** Sneak and use the tool on a multiblock you placed to change its size, tier or parts. Only the blocks that change get swapped: you pay for the new ones and get the old ones back.

**Dismantling.** Hold use on a multiblock you placed. Its blocks go to the ME network first, then your inventory.

Claims and protection mods are respected: every block is checked as if you had placed or broken it yourself.

## Languages

English, German, French, Spanish, Italian, Brazilian Portuguese, Polish, Russian, Simplified Chinese and Japanese. Everything except English was machine translated by an AI and hasn't been checked by native speakers, so some of it may be wrong. Corrections and new languages are welcome: copy `assets/formwork/lang/en_us.json` and translate it.

## Crafting

The Formwork Wand:

```
 E      E: ender pearl
IRI     I: iron ingot
 I      R: block of redstone
```

## For pack makers

Structures and variant sets are plain datapack files, so any pack can add its own.

### Structures

Put vanilla structure-block files at `data/<namespace>/formwork/structures/<path>.nbt`. Save your multiblock with a structure block and drop the file in.

- Files whose name ends in `_tier<N>` (say `crusher_tier_1` and `crusher_tier_2`) become one entry with a tier selector.
- A structure that uses a block from a mod that isn't installed is skipped.
- Give an entry a name with the lang key `formwork.structure.<file name>`. Without one, the file name is shown in readable words.

For now Formwork also reads Multi Builder Tool's folder, `data/<namespace>/mbtool_structures/`, and its `mbtool.structure.<file>` names, so packs that already ship structures for that mod keep working. This is a stopgap and will go away, so put new structures in `formwork/structures`.

### Variant sets

`data/<namespace>/formwork/variants/<name>.json` lists blocks that can replace each other:

```json
{
  "blocks": ["mekanism:basic_mechanical_pipe", "mekanism:advanced_mechanical_pipe"],
  "columns": 2,
  "walls": false,
  "match": "",
  "family": ""
}
```

- `blocks`: the interchangeable blocks. Any that aren't installed are dropped. This is the only required field.
- `columns`: how wide the choice grid is in the editor (0 fits it to the panel).
- `walls`: if true, only positions on one face of the structure's bounds can be swapped, and frame edges and corners keep their block. The set then only applies to structures that contain one of its own blocks.
- `match`: only apply to structures whose file name contains this text. You usually don't need it: when several sets list a block, the set with the most of its blocks in the structure wins.
- `family`: sets with the same family can take each other's places. All Masterful Machinery ports share one, since MM accepts ports anywhere. The editor then checks that each set keeps the blueprint's count.

### Command

`/formwork place <entry> <pos> [settings...] [block=count...]` places an entry for free (permission level 2), which is handy for testing structures.

## Building

You need Java 17. Run `./gradlew build` and the jar ends up in `build/libs/`.

## Credits

Inspired by igentuman's [Multi Builder Tool](https://github.com/igentuman/multi-builder-tool).

## License

MIT, see [LICENSE.md](LICENSE.md).
