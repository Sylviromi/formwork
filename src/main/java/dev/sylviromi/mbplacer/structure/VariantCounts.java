package dev.sylviromi.mbplacer.structure;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

/** Grid edits of per-slot choices: slots are one set's positions in order, overrides the chosen block per position. */
public final class VariantCounts {
    private VariantCounts() {
    }

    /** Positions among these slots that use this block. */
    public static int count(List<BlockPos> slots, Map<BlockPos, String> overrides, String block) {
        return (int) slots.stream().filter(p -> block.equals(overrides.get(p))).count();
    }

    /** Slots with any chosen block. */
    public static int used(List<BlockPos> slots, Map<BlockPos, String> overrides) {
        return (int) slots.stream().filter(overrides::containsKey).count();
    }

    /** Puts the block on the first slot without a choice. */
    public static void add(List<BlockPos> slots, Map<BlockPos, String> overrides, String block) {
        slots.stream().filter(p -> !overrides.containsKey(p)).findFirst().ifPresent(p -> overrides.put(p, block));
    }

    /** Takes the block off the last slot that has it. */
    public static void remove(List<BlockPos> slots, Map<BlockPos, String> overrides, String block) {
        for (int i = slots.size() - 1; i >= 0; i--)
            if (overrides.remove(slots.get(i), block)) return;
    }

    /** Gives this block every slot without a choice. */
    public static void fill(List<BlockPos> slots, Map<BlockPos, String> overrides, String block) {
        slots.forEach(p -> overrides.putIfAbsent(p, block));
    }

    public static void clear(List<BlockPos> slots, Map<BlockPos, String> overrides, String block) {
        slots.forEach(p -> overrides.remove(p, block));
    }
}
