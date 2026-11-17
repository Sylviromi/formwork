package dev.sylviromi.mbplacer.structure;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/** One block of a blueprint as plain data: registry id, blockstate properties and optional block entity nbt. */
public record BlockSpec(String id, Map<String, String> props, @Nullable CompoundTag nbt) {
    public static BlockSpec of(String id) {
        return new BlockSpec(id, Map.of(), null);
    }

    public BlockSpec withId(String newId) {
        return new BlockSpec(newId, props, null);
    }
}
