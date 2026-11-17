package dev.sylviromi.mbplacer.structure.gen;

import dev.sylviromi.mbplacer.structure.BlockSpec;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

/** Mekanism induction matrix: interior filled with providers first, then cells. */
public final class InductionMatrix implements Generator {
    private static final String M = "mekanism:";
    private static final List<String> TIERS = List.of("basic", "advanced", "elite", "ultimate");

    @Override public String id() { return "mbplacer:gen/mekanism_induction_matrix"; }
    @Override public String requiredMod() { return "mekanism"; }

    @Override
    public List<Param> params() {
        return List.of(Param.range("width", 3, 18, 5), Param.range("height", 3, 18, 5), Param.range("length", 3, 18, 5),
                new Param("cell_tier", 0, 3, 0, TIERS), new Param("provider_tier", 0, 3, 0, TIERS),
                Param.range("providers", 0, 4096, 1), Param.toggle("glass", true), Param.range("ports", 1, 8, 2));
    }

    @Override
    public Map<BlockPos, BlockSpec> build(int[] v) {
        int w = v[0], h = v[1], l = v[2], providers = v[5];
        Box box = new Box(w, h, l).shell(M + "induction_casing", v[6] == 1 ? M + "structural_glass" : M + "induction_casing");
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++)
                for (int z = 1; z < l - 1; z++)
                    box.set(x, y, z, providers-- > 0 ? M + TIERS.get(v[4]) + "_induction_provider" : M + TIERS.get(v[3]) + "_induction_cell");
        box.onWalls(box.sideCentres(h / 2), v[7], M + "induction_port");
        return box.blocks;
    }
}
