package dev.sylviromi.mbplacer.build;

import dev.sylviromi.mbplacer.structure.*;
import dev.sylviromi.mbplacer.structure.gen.Generator;
import dev.sylviromi.mbplacer.structure.gen.Param;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Turns a selection into blocks: relative specs (variants applied), then rotated world states. Same code on both sides. */
public final class Resolver {
    private Resolver() {
    }

    /** Relative blocks with generator params and variant counts applied, or null when the selection is unknown/invalid. */
    @Nullable
    public static Map<BlockPos, BlockSpec> specs(Catalog catalog, Selection sel) {
        Map<BlockPos, BlockSpec> base;
        Optional<Generator> gen = catalog.generator(sel.entry());
        if (gen.isPresent()) {
            base = gen.get().build(clampedParams(gen.get(), sel.params()));
        } else {
            Blueprint bp = catalog.blueprints.get(sel.entry());
            if (bp == null) return null;
            base = bp.blocks();
        }
        return applyVariants(catalog, sel.entry(), base, sel.counts());
    }

    public static int[] clampedParams(Generator gen, int[] given) {
        List<Param> params = gen.params();
        int[] out = new int[params.size()];
        for (int i = 0; i < out.length; i++) out[i] = i < given.length ? params.get(i).clamp(given[i]) : params.get(i).def();
        return out;
    }

    /** Slots of each variant set get the counted blocks in a fixed position order; the rest keep their blueprint block. */
    @Nullable
    static Map<BlockPos, BlockSpec> applyVariants(Catalog catalog, String entry, Map<BlockPos, BlockSpec> base, Map<String, Map<String, Integer>> counts) {
        if (counts.isEmpty()) return base;
        Map<BlockPos, BlockSpec> out = new LinkedHashMap<>(base);
        for (var setCounts : counts.entrySet()) {
            VariantSet set = catalog.variants.get(setCounts.getKey());
            if (set == null || !set.blocks().containsAll(setCounts.getValue().keySet())) return null;
            List<BlockPos> slots = base.entrySet().stream().filter(e -> catalog.variantOf(e.getValue().id(), entry) == set)
                    .map(Map.Entry::getKey).sorted().toList();
            Iterator<BlockPos> it = slots.iterator();
            for (var c : setCounts.getValue().entrySet())
                for (int i = 0; i < c.getValue() && it.hasNext(); i++) {
                    BlockPos p = it.next();
                    out.put(p, base.get(p).withId(c.getKey()));
                }
        }
        return out;
    }

    /** How many slots each variant set has in these blocks. */
    public static Map<VariantSet, Integer> slotCounts(Catalog catalog, String entry, Map<BlockPos, BlockSpec> base) {
        Map<VariantSet, Integer> out = new LinkedHashMap<>();
        base.values().forEach(s -> {
            VariantSet set = catalog.variantOf(s.id(), entry);
            if (set != null) out.merge(set, 1, Integer::sum);
        });
        return out;
    }

    /** Clockwise quarter turns from base that make a structure face d (the placement card's compass). */
    public static int turnsTo(Direction base, Direction d) {
        return Math.floorMod(d.get2DDataValue() - base.get2DDataValue(), 4);
    }

    public static Rotation rotationFor(Direction facing) {
        return switch (facing) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** World positions: rotated by the player's facing, bottom at target.y, starting at target and extending away from the player, centred sideways. */
    /** A block ready to put in the world. */
    public record Placed(BlockState state, @Nullable net.minecraft.nbt.CompoundTag nbt) {
    }

    public static Map<BlockPos, Placed> world(Map<BlockPos, BlockSpec> specs, BlockPos target, Direction facing) {
        Rotation rot = rotationFor(facing);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : specs.keySet()) {
            BlockPos r = p.rotate(rot);
            minX = Math.min(minX, r.getX()); maxX = Math.max(maxX, r.getX());
            minY = Math.min(minY, r.getY());
            minZ = Math.min(minZ, r.getZ()); maxZ = Math.max(maxZ, r.getZ());
        }
        int ox = switch (facing) { case EAST -> -minX; case WEST -> -maxX; default -> -(minX + maxX) / 2; };
        int oz = switch (facing) { case SOUTH -> -minZ; case NORTH -> -maxZ; default -> -(minZ + maxZ) / 2; };
        BlockPos offset = target.offset(ox, -minY, oz);
        Map<BlockPos, Placed> out = new TreeMap<>();
        specs.forEach((p, spec) -> {
            BlockState state = state(spec);
            if (state != null) out.put(p.rotate(rot).offset(offset), new Placed(state.rotate(rot), spec.nbt()));
        });
        return out;
    }

    @Nullable
    public static BlockState state(BlockSpec spec) {
        ResourceLocation rl = ResourceLocation.tryParse(spec.id());
        Block block = rl == null ? null : ForgeRegistries.BLOCKS.getValue(rl);
        if (block == null || !ForgeRegistries.BLOCKS.containsKey(rl)) return null;
        BlockState state = block.defaultBlockState();
        for (var e : spec.props().entrySet()) state = withProp(state, e.getKey(), e.getValue());
        return state;
    }

    private static BlockState withProp(BlockState state, String name, String value) {
        Property<?> prop = state.getBlock().getStateDefinition().getProperty(name);
        return prop == null ? state : setValue(state, prop, value);
    }

    private static <T extends Comparable<T>> BlockState setValue(BlockState state, Property<T> prop, String value) {
        return prop.getValue(value).map(v -> state.setValue(prop, v)).orElse(state);
    }
}
