package dev.sylviromi.mbplacer.structure.gen;

import dev.sylviromi.mbplacer.structure.BlockSpec;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

/** Mekanism thermal evaporation plant: fixed 4x4 footprint, open top, one controller in the wall (EvaporationValidator). */
public final class ThermalEvaporation implements Generator {
    private static final String M = "mekanism:";

    @Override public String id() { return "mbplacer:gen/mekanism_thermal_evaporation"; }
    @Override public String requiredMod() { return "mekanism"; }

    @Override
    public List<Param> params() {
        return List.of(Param.range("height", 3, 18, 18), Param.range("valves", 0, 3, 2));
    }

    @Override
    public Map<BlockPos, BlockSpec> build(int[] v) {
        int h = v[0];
        Box box = new Box(4, h, 4).shell(M + "thermal_evaporation_block", M + "thermal_evaporation_block");
        for (int x = 1; x <= 2; x++)
            for (int z = 1; z <= 2; z++) box.blocks.remove(new BlockPos(x, h - 1, z));
        box.set(2, 1, 0, M + "thermal_evaporation_controller");
        box.onWalls(box.sideCentres(1), v[1], M + "thermal_evaporation_valve");
        return box.blocks;
    }
}
