package dev.sylviromi.formwork.structure;

import dev.sylviromi.formwork.structure.gen.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorTest {
    private static int count(Map<BlockPos, BlockSpec> m, String id) {
        return (int) m.values().stream().filter(s -> s.id().equals(id)).count();
    }

    private static boolean isFrame(BlockPos p, int w, int h, int l) {
        int s = (p.getX() == 0 || p.getX() == w - 1 ? 1 : 0) + (p.getY() == 0 || p.getY() == h - 1 ? 1 : 0) + (p.getZ() == 0 || p.getZ() == l - 1 ? 1 : 0);
        return s >= 2;
    }

    @Test
    void smallestFissionReactor() {
        // width 3, height 4, length 3, glass, checker (ports replace wall blocks, so the count stays)
        var m = new FissionReactor().build(new int[]{3, 4, 3, 1, 0});
        assertEquals(36, m.size(), "34 shell blocks + 1 fuel assembly + 1 control rod");
        assertEquals("mekanismgenerators:fission_fuel_assembly", m.get(new BlockPos(1, 1, 1)).id());
        assertEquals("mekanismgenerators:control_rod_assembly", m.get(new BlockPos(1, 2, 1)).id());
    }

    @Test
    void largestFissionReactorShellAndColumns() {
        int n = 18;
        var m = new FissionReactor().build(new int[]{n, n, n, 0, 0});
        long shell = m.keySet().stream().filter(p -> p.getX() == 0 || p.getY() == 0 || p.getZ() == 0 || p.getX() == n - 1 || p.getY() == n - 1 || p.getZ() == n - 1).count();
        assertEquals(n * n * n - (n - 2) * (n - 2) * (n - 2), shell);
        m.forEach((p, s) -> { if (isFrame(p, n, n, n)) assertEquals("mekanismgenerators:fission_reactor_casing", s.id()); });
        assertEquals(4, count(m, "mekanismgenerators:fission_reactor_port"));
        // every fuel column ends in a control rod directly below the ceiling
        int rods = count(m, "mekanismgenerators:control_rod_assembly");
        assertEquals(rods * (n - 3), count(m, "mekanismgenerators:fission_fuel_assembly"));
        m.forEach((p, s) -> { if (s.id().endsWith("control_rod_assembly")) assertEquals(n - 2, p.getY()); });
    }

    @Test
    void turbineLayout() {
        // size 7, height 9, rotors 3, coils 2, glass, no condensers (2 valves, fixed)
        var m = new IndustrialTurbine().build(new int[]{1, 9, 3, 2, 1, 0});
        assertEquals(3, count(m, "mekanismgenerators:turbine_rotor"));
        assertEquals("mekanismgenerators:rotational_complex", m.get(new BlockPos(3, 4, 3)).id());
        assertEquals(24, count(m, "mekanism:pressure_disperser"), "full 5x5 layer minus the complex");
        assertEquals("mekanismgenerators:electromagnetic_coil", m.get(new BlockPos(3, 5, 3)).id(), "coil touches the complex");
        assertEquals(2, count(m, "mekanismgenerators:electromagnetic_coil"));
        assertEquals(25, count(m, "mekanismgenerators:turbine_vent"));
        assertEquals(2, count(m, "mekanismgenerators:turbine_valve"));
        for (int y = 1; y <= 3; y++) {
            var rotor = m.get(new BlockPos(3, y, 3)).nbt();
            assertEquals(2, rotor.getInt("blades"), "rotors come with blades");
            assertEquals(y - 1, rotor.getInt("position"));
        }
    }

    @Test
    void turbineAutoFillsRotorsAndCoils() {
        // size 5, height 9, rotors auto, coils auto: 5 rotors fit (height - 4), 10 blades need 3 coils at 4 blades each
        var m = new IndustrialTurbine().build(new int[]{0, 9, 0, 0, 1, 0});
        assertEquals(5, count(m, "mekanismgenerators:turbine_rotor"));
        assertEquals(3, count(m, "mekanismgenerators:electromagnetic_coil"));
    }

    @Test
    void boilerHasFullDisperserLayer() {
        var m = new ThermoelectricBoiler().build(new int[]{5, 7, 5, 3, 1, 1});
        assertEquals(9, count(m, "mekanism:pressure_disperser"));
        assertEquals(9, count(m, "mekanism:superheating_element"));
        m.forEach((p, s) -> { if (s.id().equals("mekanism:pressure_disperser")) assertEquals(3, p.getY()); });
    }

    @Test
    void matrixInteriorIsFull() {
        var m = new InductionMatrix().build(new int[]{5, 5, 5, 3, 2, 4, 1});
        assertEquals(125, m.size());
        assertEquals(4, count(m, "mekanism:elite_induction_provider"));
        assertEquals(23, count(m, "mekanism:ultimate_induction_cell"));
    }

    @Test
    void extrasTierMakesTheWholeMatrixReinforced() {
        // cells infinite (7), providers basic (0): providers are lifted to absolute, casing and ports reinforced
        var m = new InductionMatrix().build(new int[]{5, 5, 5, 7, 0, 4, 0});
        assertEquals(4, count(m, "mekanism_extras:absolute_induction_provider"));
        assertEquals(23, count(m, "mekanism_extras:infinite_induction_cell"));
        assertEquals(2, count(m, "mekanism_extras:reinforced_induction_port"));
        assertEquals(0, count(m, "mekanism:induction_casing"));
    }

    @Test
    void evaporationTierAndGlass() {
        // height 5, glass, ultimate tier (4)
        var m = new ThermalEvaporation().build(new int[]{5, 1, 4});
        String p = "morethermalevaporation:ultimate_thermal_evaporation_";
        assertEquals(p + "controller", m.get(new BlockPos(2, 1, 0)).id());
        assertEquals(2, count(m, p + "valve"));
        m.forEach((pos, s) -> { if (isFrame(pos, 4, 5, 4)) assertEquals(p + "block", s.id()); });
        assertTrue(count(m, "mekanism:structural_glass") > 0);
        assertEquals("mekanism:thermal_evaporation_controller", new ThermalEvaporation().build(new int[]{5, 0}).get(new BlockPos(2, 1, 0)).id(), "without More Thermal Evaporation there is no tier param");
    }

    @Test
    void nuclearcraftLayoutsMatchTheStructuresItShips() {
        var msr = new NuclearCraft.MoltenSaltReactor().build(new int[]{7, 7, 7, 1});
        assertEquals(125, count(msr, "nuclearcraft:msr_fuel_cell"));
        assertEquals(4, count(msr, "nuclearcraft:msr_port"));
        var turbine = new NuclearCraft.Turbine().build(new int[]{5, 17, 2, 1});
        assertEquals(360, count(turbine, "nuclearcraft:turbine_sic_sic_cmc_rotor_blade"));
        assertEquals(3, count(turbine, "nuclearcraft:turbine_port"));
        var exchanger = new NuclearCraft.HeatExchanger().build(new int[]{11, 11, 11});
        assertEquals(378, count(exchanger, "nuclearcraft:heat_exchanger_radiator"));
    }
}
