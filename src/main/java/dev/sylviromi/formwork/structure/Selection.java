package dev.sylviromi.formwork.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the player picked, stored on the tool.
 * entry: blueprint id or generator id. params: generator parameter values.
 * counts: variant set id -> (block id -> how many slots of that set use this block), filled in position order. Used by the
 * op command and older tools; the GUI writes overrides.
 * overrides: blueprint position -> the block chosen for that slot (applied after counts).
 */
public record Selection(String entry, int[] params, Map<String, Map<String, Integer>> counts, Map<BlockPos, String> overrides) {
    public static final Selection EMPTY = new Selection("", new int[0], Map.of());

    public Selection(String entry, int[] params, Map<String, Map<String, Integer>> counts) {
        this(entry, params, counts, Map.of());
    }

    public boolean isEmpty() {
        return entry.isEmpty();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("entry", entry);
        tag.putIntArray("params", params);
        CompoundTag c = new CompoundTag();
        counts.forEach((set, perBlock) -> {
            CompoundTag s = new CompoundTag();
            perBlock.forEach(s::putInt);
            c.put(set, s);
        });
        tag.put("counts", c);
        ListTag o = new ListTag();
        overrides.forEach((pos, block) -> {
            CompoundTag e = new CompoundTag();
            e.putLong("pos", pos.asLong());
            e.putString("block", block);
            o.add(e);
        });
        if (!o.isEmpty()) tag.put("overrides", o);
        return tag;
    }

    public static Selection load(CompoundTag tag) {
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        CompoundTag c = tag.getCompound("counts");
        for (String set : c.getAllKeys()) {
            CompoundTag s = c.getCompound(set);
            Map<String, Integer> perBlock = new LinkedHashMap<>();
            for (String block : s.getAllKeys()) perBlock.put(block, s.getInt(block));
            counts.put(set, perBlock);
        }
        Map<BlockPos, String> overrides = new LinkedHashMap<>();
        for (Tag t : tag.getList("overrides", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            overrides.put(BlockPos.of(e.getLong("pos")), e.getString("block"));
        }
        return new Selection(tag.getString("entry"), tag.getIntArray("params"), counts, overrides);
    }
}
