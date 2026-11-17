package dev.sylviromi.mbplacer.structure;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariantCountsTest {
    private static final List<BlockPos> SLOTS = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(2, 0, 0));

    @Test
    void gridEditsFillSlotsInOrder() {
        Map<BlockPos, String> o = new LinkedHashMap<>();
        VariantCounts.add(SLOTS, o, "speed");
        VariantCounts.add(SLOTS, o, "energy");
        assertEquals(Map.of(SLOTS.get(0), "speed", SLOTS.get(1), "energy"), o);
        VariantCounts.fill(SLOTS, o, "item");
        assertEquals("item", o.get(SLOTS.get(2)), "fill takes only free slots");
        VariantCounts.add(SLOTS, o, "speed");
        assertEquals(1, VariantCounts.count(SLOTS, o, "speed"), "no free slot left");
        VariantCounts.remove(SLOTS, o, "energy");
        assertEquals(2, VariantCounts.used(SLOTS, o));
        VariantCounts.clear(SLOTS, o, "item");
        assertEquals(Map.of(SLOTS.get(0), "speed"), o);
    }
}
