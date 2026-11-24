package dev.sylviromi.formwork.structure.gen;

import dev.sylviromi.formwork.structure.BlockSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;
import java.util.Map;

/** NuclearCraft multiblocks, laid out like the structures NuclearCraft ships (data/nuclearcraft/mbtool_structures). */
public final class NuclearCraft {
    private static final String N = "nuclearcraft:";

    private NuclearCraft() {
    }

    private static BlockSpec facing(String id, Direction d) {
        return new BlockSpec(N + id, Map.of("facing", d.getName()), null);
    }

    /** The wall block at pos faces out of the box. */
    private static Direction outward(Box b, BlockPos p) {
        if (p.getX() == 0) return Direction.WEST;
        if (p.getX() == b.w - 1) return Direction.EAST;
        if (p.getZ() == 0) return Direction.NORTH;
        return Direction.SOUTH;
    }

    private static void wall(Box b, int x, int y, int z, String id) {
        BlockPos p = new BlockPos(x, y, z);
        b.blocks.put(p, facing(id, outward(b, p)));
    }

    private static void floor(Box b, String id) {
        for (int x = 0; x < b.w; x++)
            for (int z = 0; z < b.l; z++) b.set(x, 0, z, id);
    }

    /** Molten salt reactor: a casing and glass box full of fuel cells, controller and four ports at the bottom. */
    public static final class MoltenSaltReactor implements Generator {
        @Override public String id() { return "formwork:gen/nuclearcraft_msr"; }
        @Override public String nameKey() { return "block.nuclearcraft.msr_controller"; }
        @Override public String requiredMod() { return "nuclearcraft"; }

        @Override
        public List<Param> params() {
            return List.of(Param.range("width", 5, 11, 7).named("gui.mekanism.width"), Param.range("height", 5, 11, 7).named("gui.mekanism.height"), Param.range("length", 5, 11, 7),
                    Param.toggle("glass", true).named("block.nuclearcraft.fission_reactor_glass"));
        }

        @Override
        public Map<BlockPos, BlockSpec> build(int[] v) {
            int w = v[0], h = v[1], l = v[2];
            String casing = N + "fission_reactor_casing";
            Box box = new Box(w, h, l).shell(casing, v[3] == 1 ? N + "fission_reactor_glass" : casing);
            floor(box, casing);
            for (int y = 1; y < h - 1; y++)
                for (int x = 1; x < w - 1; x++)
                    for (int z = 1; z < l - 1; z++) box.set(x, y, z, N + "msr_fuel_cell");
            wall(box, w / 2, 1, 0, "msr_controller");
            wall(box, w / 2, 3, 0, "irradiator");
            for (int z : new int[]{2, l - 3}) {
                wall(box, 0, 1, z, "msr_port");
                wall(box, w - 1, 1, z, "msr_port");
            }
            if (Math.min(w, l) >= 9)
                for (int x : new int[]{2, w - 3}) {
                    wall(box, x, 1, 0, "msr_port");
                    wall(box, x, 1, l - 1, "msr_port");
                }
            return box.blocks;
        }
    }

    /** Solid fuel fission reactor: fuel cells in a checker, water heat sinks in between (each touches a cell), controller and ports in the empty bottom layer. */
    public static final class FissionReactor implements Generator {
        @Override public String id() { return "formwork:gen/nuclearcraft_fission_reactor"; }
        @Override public String nameKey() { return "block.nuclearcraft.fission_reactor_controller"; }
        @Override public String requiredMod() { return "nuclearcraft"; }

        @Override
        public List<Param> params() {
            return List.of(Param.range("width", 5, 26, 7).named("gui.mekanism.width"), Param.range("height", 5, 26, 7).named("gui.mekanism.height"), Param.range("length", 5, 26, 7),
                    Param.toggle("glass", true).named("block.nuclearcraft.fission_reactor_glass"),
                    new Param("heat_sink", 0, 1, 0, List.of("block.nuclearcraft.water_heat_sink", "block.nuclearcraft.active_water_heat_sink")));
        }

        @Override
        public Map<BlockPos, BlockSpec> build(int[] v) {
            int w = v[0], h = v[1], l = v[2];
            String casing = N + "fission_reactor_casing";
            Box box = new Box(w, h, l).shell(casing, v[3] == 1 ? N + "fission_reactor_glass" : casing);
            floor(box, casing);
            String sink = N + (v[4] == 1 ? "active_water_heat_sink" : "water_heat_sink");
            for (int y = 2; y < h - 2; y++)
                for (int x = 1; x < w - 1; x++)
                    for (int z = 1; z < l - 1; z++) box.set(x, y, z, (x + z) % 2 == 0 ? N + "fission_reactor_solid_fuel_cell" : sink);
            wall(box, w / 2, 1, 0, "fission_reactor_controller");
            for (int z : new int[]{2, l - 3}) {
                wall(box, 0, 1, z, "fission_reactor_port");
                wall(box, w - 1, 1, z, "fission_reactor_port");
            }
            return box.blocks;
        }
    }

    /** Heat exchanger: casing frame, radiators on the walls and ceiling, tubes inside, hot and cold ports on one wall. */
    public static final class HeatExchanger implements Generator {
        @Override public String id() { return "formwork:gen/nuclearcraft_heat_exchanger"; }
        @Override public String nameKey() { return "block.nuclearcraft.heat_exchanger_controller"; }
        @Override public String requiredMod() { return "nuclearcraft"; }

        @Override
        public List<Param> params() {
            return List.of(Param.range("width", 5, 15, 11).named("gui.mekanism.width"), Param.range("height", 5, 15, 11).named("gui.mekanism.height"), Param.range("length", 7, 15, 11));
        }

        @Override
        public Map<BlockPos, BlockSpec> build(int[] v) {
            int w = v[0], h = v[1], l = v[2];
            Box box = new Box(w, h, l).shell(N + "heat_exchanger_casing", N + "heat_exchanger_radiator");
            floor(box, N + "heat_exchanger_casing");
            for (int y = 1; y < h - 1; y++)
                for (int x = 1; x < w - 1; x++)
                    for (int z = 1; z < l - 1; z++) box.set(x, y, z, N + "heat_exchanger");
            int e = w - 1;
            for (int y = 1; y <= 3; y++)
                for (int z = 1; z < l - 1; z++) box.set(e, y, z, N + "heat_exchanger_casing");
            wall(box, e, 1, 1, "heat_exchanger_hot_coolant_port");
            wall(box, e, 1, l - 2, "heat_exchanger_hot_coolant_port");
            wall(box, e, 1, 3, "heat_exchanger_cold_coolant_port");
            wall(box, e, 3, 1, "heat_exchanger_cold_coolant_port");
            wall(box, e, 3, l - 2, "heat_exchanger_cold_coolant_port");
            wall(box, e, 2, l - 3, "heat_exchanger_controller");
            return box.blocks;
        }
    }

    /**
     * Steam turbine, standing up: bearings on both ends of a rotor shaft, four blade arms along all of it, coil rings around each bearing
     * (magnesium, beryllium, gold, copper, aluminium by distance), controller and ports on one wall.
     */
    public static final class Turbine implements Generator {
        private static final String[] COILS = {"magnesium", "beryllium", "gold", "copper", "aluminum"};

        @Override public String id() { return "formwork:gen/nuclearcraft_turbine"; }
        @Override public String nameKey() { return "block.nuclearcraft.turbine_controller"; }
        @Override public String requiredMod() { return "nuclearcraft"; }

        @Override
        public List<Param> params() {
            return List.of(new Param("size", 0, 6, 0, List.of("5", "7", "9", "11", "13", "15", "17")), Param.range("height", 5, 24, 9).named("gui.mekanism.height"),
                    new Param("blade", 0, 2, 0, List.of("block.nuclearcraft.turbine_steel_rotor_blade", "block.nuclearcraft.turbine_extreme_rotor_blade", "block.nuclearcraft.turbine_sic_sic_cmc_rotor_blade")),
                    Param.toggle("glass", true).named("block.nuclearcraft.turbine_glass"));
        }

        @Override
        public Map<BlockPos, BlockSpec> build(int[] v) {
            int s = 5 + 2 * v[0], h = v[1], c = s / 2;
            String casing = N + "turbine_casing";
            Box box = new Box(s, h, s).shell(casing, v[3] == 1 ? N + "turbine_glass" : casing);
            floor(box, casing);
            String blade = N + new String[]{"turbine_steel_rotor_blade", "turbine_extreme_rotor_blade", "turbine_sic_sic_cmc_rotor_blade"}[v[2]];
            Map<String, String> axisY = Map.of("axis", "y");
            for (int y : new int[]{0, h - 1}) {
                for (int x = 1; x < s - 1; x++)
                    for (int z = 1; z < s - 1; z++) {
                        int d = Math.abs(x - c) + Math.abs(z - c);
                        if (d >= 1 && d <= COILS.length) box.set(x, y, z, N + "turbine_" + COILS[d - 1] + "_coil");
                    }
                box.blocks.put(new BlockPos(c, y, c), new BlockSpec(N + "turbine_bearing", axisY, null));
            }
            for (int y = 1; y < h - 1; y++) {
                box.blocks.put(new BlockPos(c, y, c), new BlockSpec(N + "turbine_rotor_shaft", Map.of("facing", "up"), null));
                for (Direction d : Direction.Plane.HORIZONTAL)
                    for (int k = 1; k < c; k++)
                        box.blocks.put(new BlockPos(c + k * d.getStepX(), y, c + k * d.getStepZ()), new BlockSpec(blade, Map.of("facing", d.getOpposite().getName()), null));
            }
            wall(box, c, 1, s - 1, "turbine_controller");
            wall(box, 1, 1, s - 1, "turbine_port");
            wall(box, s - 2, 1, s - 1, "turbine_port");
            if (s >= 11) wall(box, s - 4, 1, s - 1, "turbine_port");
            return box.blocks;
        }
    }
}
