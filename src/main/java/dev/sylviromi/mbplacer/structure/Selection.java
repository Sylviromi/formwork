package dev.sylviromi.mbplacer.structure;

import net.minecraft.nbt.CompoundTag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the player picked, stored on the tool.
 * entry: blueprint id or generator id. params: generator parameter values.
 * counts: variant set id -> (block id -> how many slots of that set use this block); the rest use the default.
 */
public record Selection(String entry, int[] params, Map<String, Map<String, Integer>> counts) {
    public static final Selection EMPTY = new Selection("", new int[0], Map.of());

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
        return new Selection(tag.getString("entry"), tag.getIntArray("params"), counts);
    }
}
