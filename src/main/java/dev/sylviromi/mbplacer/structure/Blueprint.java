package dev.sylviromi.mbplacer.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A fixed structure: relative position to block. Air is never stored. */
public record Blueprint(String id, Map<BlockPos, BlockSpec> blocks) {
    private static final List<String> SKIPPED = List.of("minecraft:air", "minecraft:structure_void", "minecraft:cave_air");

    /** Reads the vanilla structure-block format (size, palette or palettes, blocks). */
    public static Blueprint fromStructureNbt(String id, CompoundTag tag) {
        ListTag palette = tag.contains("palette") ? tag.getList("palette", Tag.TAG_COMPOUND)
                : tag.getList("palettes", Tag.TAG_LIST).getList(0);
        BlockSpec[] specs = new BlockSpec[palette.size()];
        for (int i = 0; i < specs.length; i++) {
            CompoundTag entry = palette.getCompound(i);
            Map<String, String> props = new HashMap<>();
            CompoundTag p = entry.getCompound("Properties");
            for (String key : p.getAllKeys()) props.put(key, p.getString(key));
            specs[i] = new BlockSpec(entry.getString("Name"), Map.copyOf(props), null);
        }
        Map<BlockPos, BlockSpec> blocks = new LinkedHashMap<>();
        for (Tag t : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag b = (CompoundTag) t;
            int state = b.getInt("state");
            if (state < 0 || state >= specs.length || SKIPPED.contains(specs[state].id())) continue;
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            BlockSpec spec = specs[state];
            if (b.contains("nbt")) spec = new BlockSpec(spec.id(), spec.props(), b.getCompound("nbt"));
            blocks.put(new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)), spec);
        }
        return new Blueprint(id, blocks);
    }
}
