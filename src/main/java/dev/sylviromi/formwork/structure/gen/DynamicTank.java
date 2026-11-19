package dev.sylviromi.formwork.structure.gen;

import dev.sylviromi.formwork.structure.BlockSpec;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

/** Mekanism dynamic tank: a hollow box with valves. */
public final class DynamicTank implements Generator {
    private static final String M = "mekanism:";

    @Override public String id() { return "formwork:gen/mekanism_dynamic_tank"; }
    @Override public String nameKey() { return "gui.mekanism.dynamic_tank"; }
    @Override public String requiredMod() { return "mekanism"; }

    @Override
    public List<Param> params() {
        return List.of(Param.range("width", 3, 18, 5).named("gui.mekanism.width"), Param.range("height", 3, 18, 5).named("gui.mekanism.height"), Param.range("length", 3, 18, 5),
                Param.toggle("glass", true).named("block.mekanism.structural_glass"));
    }

    @Override
    public Map<BlockPos, BlockSpec> build(int[] v) {
        Box box = new Box(v[0], v[1], v[2]).shell(M + "dynamic_tank", v[3] == 1 ? M + "structural_glass" : M + "dynamic_tank");
        box.onWalls(box.sideCentres(1), 2, M + "dynamic_valve");
        return box.blocks;
    }
}
