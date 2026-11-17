package dev.sylviromi.mbplacer.structure.gen;

import dev.sylviromi.mbplacer.structure.BlockSpec;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

/** Builds a multiblock of variable shape from parameter values. Values are already clamped to the params. */
public interface Generator {
    String id();

    /** Lang key of the owning mod's name for this multiblock; empty: ours (mbplacer.gen.*). */
    default String nameKey() {
        return "";
    }

    /** Mod id that must be loaded for this generator to be offered. */
    String requiredMod();

    List<Param> params();

    Map<BlockPos, BlockSpec> build(int[] values);
}
