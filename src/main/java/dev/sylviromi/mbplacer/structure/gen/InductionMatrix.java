package dev.sylviromi.mbplacer.structure.gen;

import dev.sylviromi.mbplacer.structure.BlockSpec;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mekanism induction matrix: interior filled with providers first, then cells.
 * mekanism_extras tiers (absolute and up) only form in its reinforced casing, which takes no Mekanism cells (ExtraMatrixValidator).
 */
public final class InductionMatrix implements Generator {
    private static final String M = "mekanism:", X = "mekanism_extras:";
    private static final List<String> TIERS = List.of("basic", "advanced", "elite", "ultimate"),
            EXTRA_TIERS = List.of("absolute", "supreme", "cosmic", "infinite");

    @Override public String id() { return "mbplacer:gen/mekanism_induction_matrix"; }
    @Override public String nameKey() { return "matrix.mekanism.induction_matrix"; }
    @Override public String requiredMod() { return "mekanism"; }

    private static List<String> tiers() {
        if (ModList.get() != null && !ModList.get().isLoaded("mekanism_extras")) return TIERS;
        List<String> all = new ArrayList<>(TIERS);
        all.addAll(EXTRA_TIERS);
        return all;
    }

    @Override
    public List<Param> params() {
        int top = tiers().size() - 1;
        // options are named by the blocks themselves, so the owning mods' translations apply
        List<String> cells = new ArrayList<>(), providers = new ArrayList<>();
        for (int t = 0; t <= top; t++) {
            cells.add(blockKey(tierBlock(t, "_induction_cell")));
            providers.add(blockKey(tierBlock(t, "_induction_provider")));
        }
        return List.of(Param.range("width", 3, 18, 5).named("gui.mekanism.width"), Param.range("height", 3, 18, 5).named("gui.mekanism.height"), Param.range("length", 3, 18, 5),
                new Param("cell_tier", 0, top, 0, cells), new Param("provider_tier", 0, top, 0, providers),
                Param.range("providers", 0, 4096, 1), Param.toggle("glass", true).named("block.mekanism.structural_glass"));
    }

    private static String blockKey(String id) {
        return Util.makeDescriptionId("block", ResourceLocation.tryParse(id));
    }

    private static String tierBlock(int tier, String kind) {
        return tier < TIERS.size() ? M + TIERS.get(tier) + kind : X + EXTRA_TIERS.get(tier - TIERS.size()) + kind;
    }

    @Override
    public Map<BlockPos, BlockSpec> build(int[] v) {
        int w = v[0], h = v[1], l = v[2], providers = v[5];
        boolean reinforced = Math.max(v[3], v[4]) >= TIERS.size();
        // reinforced: Mekanism tiers would not form, so they become the lowest extras tier
        int cell = reinforced ? Math.max(v[3], TIERS.size()) : v[3], provider = reinforced ? Math.max(v[4], TIERS.size()) : v[4];
        String casing = reinforced ? X + "reinforced_induction_casing" : M + "induction_casing";
        Box box = new Box(w, h, l).shell(casing, v[6] == 1 ? M + "structural_glass" : casing);
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++)
                for (int z = 1; z < l - 1; z++)
                    box.set(x, y, z, providers-- > 0 ? tierBlock(provider, "_induction_provider") : tierBlock(cell, "_induction_cell"));
        box.onWalls(box.sideCentres(h / 2), 2, reinforced ? X + "reinforced_induction_port" : M + "induction_port");
        return box.blocks;
    }
}
