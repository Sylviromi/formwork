package dev.sylviromi.mbplacer.structure;

import java.util.Map;

/** Edits of one variant set's counts (block id -> slots) with `slots` in total; never goes below 0 or above the free slots. */
public final class VariantCounts {
    private VariantCounts() {
    }

    public static int used(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Adds d (negative removes), limited by the free slots. */
    public static void add(Map<String, Integer> counts, String block, int d, int slots) {
        int now = counts.getOrDefault(block, 0);
        int next = Math.max(0, now + Math.min(d, slots - used(counts)));
        if (next == 0) counts.remove(block);
        else counts.put(block, next);
    }

    /** Gives this block every free slot. */
    public static void fill(Map<String, Integer> counts, String block, int slots) {
        add(counts, block, slots, slots);
    }
}
