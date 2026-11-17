package dev.sylviromi.mbplacer.structure.gen;

import dev.sylviromi.mbplacer.structure.BlockSpec;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Mekanism thermoelectric boiler: heaters at the bottom, a full disperser layer splitting water and steam. */
public final class ThermoelectricBoiler implements Generator {
    private static final String M = "mekanism:";

    @Override public String id() { return "mbplacer:gen/mekanism_boiler"; }
    @Override public String nameKey() { return "boiler.mekanism.thermoelectric_boiler"; }
    @Override public String requiredMod() { return "mekanism"; }

    @Override
    public List<Param> params() {
        return List.of(Param.range("width", 3, 18, 7).named("gui.mekanism.width"), Param.range("height", 5, 18, 9).named("gui.mekanism.height"), Param.range("length", 3, 18, 7),
                Param.range("disperser_height", 2, 16, 4), Param.range("heater_layers", 0, 15, 1),
                Param.toggle("glass", true).named("block.mekanism.structural_glass"));
    }

    @Override
    public Map<BlockPos, BlockSpec> build(int[] v) {
        int w = v[0], h = v[1], l = v[2];
        int disperserY = Math.max(2, Math.min(v[3], h - 3));
        Box box = new Box(w, h, l).shell(M + "boiler_casing", v[5] == 1 ? M + "structural_glass" : M + "boiler_casing");
        for (int y = 1; y <= Math.min(v[4], disperserY - 1); y++) box.layer(y, M + "superheating_element");
        box.layer(disperserY, M + "pressure_disperser");
        List<BlockPos> walls = new ArrayList<>(box.sideCentres(1));
        walls.addAll(box.sideCentres(h - 2));
        box.onWalls(walls, 4, M + "boiler_valve");
        return box.blocks;
    }
}
