package dev.sylviromi.mbplacer.build;

import dev.sylviromi.mbplacer.structure.BlockSpec;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.VariantSet;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariantTest {
    private static final String NULL = "voidminers:null_modifier", SPEED = "voidminers:verdium_speed_modifier", ENERGY = "voidminers:verdium_energy_modifier";

    private static final String MINER = "mbtool:voidminer/voidminer_tier3_verdium";

    @Test
    void scopedSetWinsForItsStructure() {
        assertEquals("mbplacer:voidminer_modifiers", catalog().variantOf(NULL, MINER).id());
        assertEquals("mbplacer:solar_modifiers", catalog().variantOf(NULL, "mbtool:voidminer/solarpanel_tier_3_verdium").id());
    }

    private static Catalog catalog() {
        Catalog c = new Catalog();
        c.addVariants(new VariantSet("mbplacer:solar_modifiers", List.of(NULL, "voidminers:solar_verdium_weather_modifier"), "solar", 0));
        c.addVariants(new VariantSet("mbplacer:voidminer_modifiers", List.of(NULL, SPEED, ENERGY), "voidminer", 0));
        return c;
    }

    private static Map<BlockPos, BlockSpec> base() {
        Map<BlockPos, BlockSpec> m = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) m.put(new BlockPos(i, 0, 0), BlockSpec.of(NULL));
        m.put(new BlockPos(0, 1, 0), BlockSpec.of("voidminers:verdium_frame"));
        return m;
    }

    @Test
    void countsReplaceSlotsAndTheRestStayDefault() {
        var out = Resolver.applyVariants(catalog(), MINER, base(), Map.of("mbplacer:voidminer_modifiers", Map.of(SPEED, 2, ENERGY, 1)));
        assertNotNull(out);
        assertEquals(2, out.values().stream().filter(s -> s.id().equals(SPEED)).count());
        assertEquals(1, out.values().stream().filter(s -> s.id().equals(ENERGY)).count());
        assertEquals(1, out.values().stream().filter(s -> s.id().equals(NULL)).count());
        assertEquals("voidminers:verdium_frame", out.get(new BlockPos(0, 1, 0)).id());
    }

    @Test
    void countsBeyondSlotsAreCapped() {
        var out = Resolver.applyVariants(catalog(), MINER, base(), Map.of("mbplacer:voidminer_modifiers", Map.of(SPEED, 99)));
        assertEquals(4, out.values().stream().filter(s -> s.id().equals(SPEED)).count());
    }

    @Test
    void blockOutsideTheSetIsRejected() {
        assertNull(Resolver.applyVariants(catalog(), MINER, base(), Map.of("mbplacer:voidminer_modifiers", Map.of("minecraft:tnt", 1))));
        assertNull(Resolver.applyVariants(catalog(), MINER, base(), Map.of("mbplacer:unknown_set", Map.of(SPEED, 1))));
    }

    @Test
    void slotsFindTheSetAndCountsBecomeOverrides() {
        var slots = Resolver.slots(catalog(), MINER, base());
        assertEquals(4, slots.size());
        var o = Resolver.countsToOverrides(slots, Map.of("mbplacer:voidminer_modifiers", Map.of(SPEED, 2)));
        assertEquals(Map.of(new BlockPos(0, 0, 0), SPEED, new BlockPos(1, 0, 0), SPEED), o, "first slots in position order");
    }

    private static final String E_IN = "mm:tiny_energy_port_input", I_IN = "mm:tiny_item_port_input", MM = "mbtool:dev_mm/water_condenser_tier_1";

    /** Two MM-style sets in one family, two energy slots in the blueprint. */
    private static Catalog ports() {
        Catalog c = new Catalog();
        c.addVariants(new VariantSet("mbplacer:mm_energy_port_input", List.of(E_IN, "mm:gigantic_energy_port_input"), "", 0, "mm_ports"));
        c.addVariants(new VariantSet("mbplacer:mm_item_port_input", List.of(I_IN, "mm:gigantic_item_port_input"), "", 0, "mm_ports"));
        return c;
    }

    @Test
    void familyOverridesAndTheStructureCheck() {
        Map<BlockPos, BlockSpec> base = Map.of(new BlockPos(0, 0, 0), BlockSpec.of(E_IN), new BlockPos(1, 0, 0), BlockSpec.of(E_IN));
        var slots = Resolver.slots(ports(), MM, base);
        var swapped = Resolver.applyOverrides(ports(), MM, base, base, Map.of(new BlockPos(1, 0, 0), I_IN, new BlockPos(0, 0, 0), "minecraft:tnt"));
        assertEquals(E_IN, swapped.get(new BlockPos(0, 0, 0)).id(), "blocks outside the family are ignored");
        assertEquals(I_IN, swapped.get(new BlockPos(1, 0, 0)).id(), "any set of the family may take the slot");
        var checks = Resolver.check(ports(), MM, slots, swapped);
        assertTrue(checks.stream().anyMatch(c -> c.set().id().endsWith("energy_port_input") && c.required() == 2 && c.actual() == 1));
        assertTrue(checks.stream().anyMatch(c -> c.set().id().endsWith("item_port_input") && c.required() == 0 && c.actual() == 1));
        assertTrue(Resolver.check(ports(), MM, slots, base).stream().allMatch(Resolver.Check::ok));
    }
}
