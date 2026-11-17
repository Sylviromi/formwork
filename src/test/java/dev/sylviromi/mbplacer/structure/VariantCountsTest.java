package dev.sylviromi.mbplacer.structure;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariantCountsTest {
    @Test
    void addFillAndRemoveStayWithinSlots() {
        Map<String, Integer> c = new LinkedHashMap<>();
        VariantCounts.add(c, "speed", 4, 24);
        VariantCounts.add(c, "energy", 30, 24);
        assertEquals(Map.of("speed", 4, "energy", 20), c, "add stops at the free slots");
        VariantCounts.add(c, "energy", -25, 24);
        assertFalse(c.containsKey("energy"), "zero counts are dropped");
        VariantCounts.fill(c, "item", 24);
        assertEquals(20, c.get("item"));
        assertEquals(24, VariantCounts.used(c));
    }
}
