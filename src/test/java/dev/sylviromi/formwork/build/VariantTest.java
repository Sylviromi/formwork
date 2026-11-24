package dev.sylviromi.formwork.build;

import dev.sylviromi.formwork.structure.BlockSpec;
import dev.sylviromi.formwork.structure.Catalog;
import dev.sylviromi.formwork.structure.VariantSet;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariantTest {
    private static final String NULL = "voidminers:null_modifier", SPEED = "voidminers:verdium_speed_modifier", ENERGY = "voidminers:verdium_energy_modifier";

    private static final String MINER = "examplepack:voidminer/voidminer_tier3_verdium";

    @Test
    void scopedSetWinsForItsStructure() {
        assertEquals("formwork:voidminer_modifiers", catalog().variantOf(NULL, MINER).id());
        assertEquals("formwork:solar_modifiers", catalog().variantOf(NULL, "examplepack:voidminer/solarpanel_tier_3_verdium").id());
    }

    private static Catalog catalog() {
        Catalog c = new Catalog();
        c.addVariants(new VariantSet("formwork:solar_modifiers", List.of(NULL, "voidminers:solar_verdium_weather_modifier"), "solar", 0, "", false));
        c.addVariants(new VariantSet("formwork:voidminer_modifiers", List.of(NULL, SPEED, ENERGY), "voidminer", 0, "", false));
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
        var out = Resolver.applyVariants(catalog(), MINER, base(), Map.of("formwork:voidminer_modifiers", Map.of(SPEED, 2, ENERGY, 1)));
        assertNotNull(out);
        assertEquals(2, out.values().stream().filter(s -> s.id().equals(SPEED)).count());
        assertEquals(1, out.values().stream().filter(s -> s.id().equals(ENERGY)).count());
        assertEquals(1, out.values().stream().filter(s -> s.id().equals(NULL)).count());
        assertEquals("voidminers:verdium_frame", out.get(new BlockPos(0, 1, 0)).id());
    }

    @Test
    void countsBeyondSlotsAreCapped() {
        var out = Resolver.applyVariants(catalog(), MINER, base(), Map.of("formwork:voidminer_modifiers", Map.of(SPEED, 99)));
        assertEquals(4, out.values().stream().filter(s -> s.id().equals(SPEED)).count());
    }

    @Test
    void blockOutsideTheSetIsRejected() {
        assertNull(Resolver.applyVariants(catalog(), MINER, base(), Map.of("formwork:voidminer_modifiers", Map.of("minecraft:tnt", 1))));
        assertNull(Resolver.applyVariants(catalog(), MINER, base(), Map.of("formwork:unknown_set", Map.of(SPEED, 1))));
    }

    @Test
    void slotsFindTheSetAndCountsBecomeOverrides() {
        var slots = Resolver.slots(catalog(), MINER, base());
        assertEquals(4, slots.size());
        var o = Resolver.countsToOverrides(slots, Map.of("formwork:voidminer_modifiers", Map.of(SPEED, 2)));
        assertEquals(Map.of(new BlockPos(0, 0, 0), SPEED, new BlockPos(1, 0, 0), SPEED), o, "first slots in position order");
    }

    @Test
    void wallSetsLeaveFrameEdgesAlone() {
        String casing = "mekanism:dynamic_tank", valve = "mekanism:dynamic_valve", entry = "formwork:gen/mekanism_dynamic_tank";
        Catalog c = new Catalog();
        c.addVariants(new VariantSet("formwork:mekanism_dynamic_tank_walls", List.of(casing, valve), "", 0, "", true));
        Map<BlockPos, BlockSpec> base = new LinkedHashMap<>();
        for (int x = 0; x < 3; x++)
            for (int y = 0; y < 3; y++)
                for (int z = 0; z < 3; z++) if (x != 1 || y != 1 || z != 1) base.put(new BlockPos(x, y, z), BlockSpec.of(casing));
        var slots = Resolver.slots(c, entry, base);
        assertEquals(6, slots.size(), "only the six face centres of a 3x3x3 shell");
        var out = Resolver.applyOverrides(c, entry, base, base, Map.of(new BlockPos(1, 1, 0), valve, new BlockPos(0, 0, 0), valve));
        assertEquals(valve, out.get(new BlockPos(1, 1, 0)).id());
        assertEquals(casing, out.get(new BlockPos(0, 0, 0)).id(), "a corner keeps its casing");
    }

    @Test
    void sharedGlassGoesToTheMultiblockItIsPartOf() {
        String glass = "mekanism:structural_glass", boiler = "mekanism:boiler_casing", turbine = "mekanismgenerators:turbine_casing";
        Catalog c = new Catalog();
        c.addVariants(new VariantSet("formwork:turbine_walls", List.of(turbine, glass), "", 0, "", true));
        c.addVariants(new VariantSet("formwork:boiler_walls", List.of(boiler, glass), "", 0, "", true));
        assertEquals("formwork:boiler_walls", c.variantOf(glass, "anypack:big_steam_thing", java.util.Set.of(boiler, glass)).id(), "the file name does not matter");
        assertNull(c.variantOf(glass, "anypack:sps", java.util.Set.of(glass, "mekanism:sps_casing")), "glass in a multiblock no set is for");
    }

    private static final String E_IN ="mm:tiny_energy_port_input", I_IN = "mm:tiny_item_port_input", MM = "examplepack:dev_mm/water_condenser_tier_1";

    /** Two MM-style sets in one family, two energy slots in the blueprint. */
    private static Catalog ports() {
        Catalog c = new Catalog();
        c.addVariants(new VariantSet("formwork:mm_energy_port_input", List.of(E_IN, "mm:gigantic_energy_port_input"), "", 0, "mm_ports", false));
        c.addVariants(new VariantSet("formwork:mm_item_port_input", List.of(I_IN, "mm:gigantic_item_port_input"), "", 0, "mm_ports", false));
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

    private static Map<BlockPos, BlockSpec> build(dev.sylviromi.formwork.structure.gen.Generator g) {
        return g.build(Resolver.clampedParams(g, new int[0]));
    }

    @Test
    void shippedWallSetsGiveHatchSlotsOnGeneratedStructures() throws Exception {
        Catalog c = new Catalog();
        var dir = java.nio.file.Path.of("src/main/resources/data/formwork/formwork/variants");
        try (var files = java.nio.file.Files.list(dir)) {
            for (var f : files.toList()) {
                var o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
                List<String> blocks = new java.util.ArrayList<>();
                o.getAsJsonArray("blocks").forEach(e -> blocks.add(e.getAsString()));
                String name = f.getFileName().toString().replace(".json", "");
                c.addVariants(new VariantSet("formwork:" + name, blocks, o.has("match") ? o.get("match").getAsString() : "", 0, o.has("family") ? o.get("family").getAsString() : "", o.has("walls") && o.get("walls").getAsBoolean()));
            }
        }
        var gens = Map.of("formwork:gen/mekanism_turbine", new dev.sylviromi.formwork.structure.gen.IndustrialTurbine().build(new int[]{1, 9, 3, 2, 1, 0}),
                "formwork:gen/nuclearcraft_turbine", build(new dev.sylviromi.formwork.structure.gen.NuclearCraft.Turbine()),
                "formwork:gen/nuclearcraft_msr", build(new dev.sylviromi.formwork.structure.gen.NuclearCraft.MoltenSaltReactor()));
        gens.forEach((entry, base) -> {
            var slots = Resolver.slots(c, entry, base);
            long ports = base.entrySet().stream().filter(e -> e.getValue().id().matches(".*(valve|port)")).filter(e -> slots.containsKey(e.getKey())).count();
            assertTrue(ports > 0, entry + " ports are slots");
            assertTrue(slots.size() > ports, entry + " casing is a slot");
        });
    }
}
