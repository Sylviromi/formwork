package dev.sylviromi.formwork.structure.gen;

import dev.sylviromi.formwork.structure.BlockSpec;
import net.minecraft.core.BlockPos;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mekanism thermal evaporation plant: fixed 4x4 footprint, open top, one controller in the wall (EvaporationValidator).
 * With More Thermal Evaporation the tier picks its basic..creative blocks (same 4x4 shape, MoreThermalEvaporationValidator).
 */
public final class ThermalEvaporation implements Generator {
    private static final String M = "mekanism:", MTE = "morethermalevaporation";
    private static final List<String> TIERS = List.of("basic", "advanced", "elite", "ultimate", "creative");

    @Override public String id() { return "formwork:gen/mekanism_thermal_evaporation"; }
    @Override public String nameKey() { return "evaporation.mekanism.evaporation_plant"; }
    @Override public String requiredMod() { return "mekanism"; }

    private static boolean tiers() {
        return ModList.get() == null || ModList.get().isLoaded(MTE);
    }

    @Override
    public List<Param> params() {
        List<Param> out = new ArrayList<>(List.of(Param.range("height", 3, 18, 18).named("gui.mekanism.height"),
                Param.toggle("glass", false).named("block.mekanism.structural_glass")));
        if (tiers()) {
            List<String> labels = new ArrayList<>(List.of("evaporation.mekanism.evaporation_plant"));
            TIERS.forEach(t -> labels.add("evaporation." + MTE + "." + t + "_evaporation_plant"));
            out.add(new Param("tier", 0, TIERS.size(), 0, labels));
        }
        return out;
    }

    @Override
    public Map<BlockPos, BlockSpec> build(int[] v) {
        int h = v[0];
        int tier = v.length > 2 ? v[2] : 0;
        String p = tier == 0 ? M + "thermal_evaporation_" : MTE + ":" + TIERS.get(tier - 1) + "_thermal_evaporation_";
        Box box = new Box(4, h, 4).shell(p + "block", v[1] == 1 ? M + "structural_glass" : p + "block");
        for (int x = 1; x <= 2; x++)
            for (int z = 1; z <= 2; z++) box.blocks.remove(new BlockPos(x, h - 1, z));
        box.set(2, 1, 0, p + "controller");
        box.onWalls(box.sideCentres(1), 2, p + "valve");
        return box.blocks;
    }
}
