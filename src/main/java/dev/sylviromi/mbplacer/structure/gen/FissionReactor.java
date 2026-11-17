package dev.sylviromi.mbplacer.structure.gen;

import dev.sylviromi.mbplacer.structure.BlockSpec;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Mekanism fission reactor: casing frame, fuel assembly columns topped by a control rod. */
public final class FissionReactor implements Generator {
    private static final String G = "mekanismgenerators:";

    @Override public String id() { return "mbplacer:gen/mekanism_fission_reactor"; }
    @Override public String nameKey() { return "fission.mekanismgenerators.fission_reactor"; }
    @Override public String requiredMod() { return "mekanismgenerators"; }

    @Override
    public List<Param> params() {
        return List.of(Param.range("width", 3, 18, 7).named("gui.mekanism.width"), Param.range("height", 4, 18, 7).named("gui.mekanism.height"), Param.range("length", 3, 18, 7),
                Param.toggle("glass", true).named("block.mekanismgenerators.reactor_glass"),
                new Param("fuel_layout", 0, 2, 0, List.of("checker", "spaced", "full")),
                Param.range("ports", 0, 8, 4).named("block.mekanismgenerators.fission_reactor_port"), Param.range("logic_adapters", 0, 4, 0).named("block.mekanismgenerators.fission_reactor_logic_adapter"));
    }

    @Override
    public Map<BlockPos, BlockSpec> build(int[] v) {
        int w = v[0], h = v[1], l = v[2], layout = v[4];
        Box box = new Box(w, h, l).shell(G + "fission_reactor_casing", v[3] == 1 ? G + "reactor_glass" : G + "fission_reactor_casing");
        for (int x = 1; x < w - 1; x++)
            for (int z = 1; z < l - 1; z++) {
                boolean column = switch (layout) {
                    case 0 -> (x + z) % 2 == 0;
                    case 1 -> x % 2 == 1 && z % 2 == 1;
                    default -> true;
                };
                if (!column) continue;
                for (int y = 1; y < h - 2; y++) box.set(x, y, z, G + "fission_fuel_assembly");
                box.set(x, h - 2, z, G + "control_rod_assembly");
            }
        List<BlockPos> walls = new ArrayList<>(box.sideCentres(1));
        walls.addAll(box.sideCentres(h - 2));
        box.onWalls(walls, v[5], G + "fission_reactor_port");
        box.onWalls(box.sideCentres(h / 2), v[6], G + "fission_reactor_logic_adapter");
        return box.blocks;
    }
}
