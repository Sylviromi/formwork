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
        Map<BlockPos, BlockSpec> counted = applyVariants(catalog, sel.entry(), base, sel.counts());
        return counted == null ? null : applyOverrides(catalog, sel.entry(), base, counted, sel.overrides());
    }

    /** Puts each chosen block on its slot, when the slot's family allows it; other overrides are ignored (e.g. after a size change). */
    static Map<BlockPos, BlockSpec> applyOverrides(Catalog catalog, String entry, Map<BlockPos, BlockSpec> base, Map<BlockPos, BlockSpec> blocks,
                                                   Map<BlockPos, String> overrides) {
        if (overrides.isEmpty()) return blocks;
        Map<BlockPos, BlockSpec> out = new LinkedHashMap<>(blocks);
        overrides.forEach((pos, block) -> {
            BlockSpec spec = base.get(pos);
            VariantSet set = spec == null ? null : catalog.variantOf(spec.id(), entry);
            if (set != null && catalog.family(set, entry).stream().anyMatch(f -> f.blocks().contains(block))) out.put(pos, spec.withId(block));
        });
        return out;
    }

    /** Swappable positions of the unmodified structure and the set each belongs to, in position order. */
    public static Map<BlockPos, VariantSet> slots(Catalog catalog, String entry, Map<BlockPos, BlockSpec> base) {
        Map<BlockPos, VariantSet> out = new TreeMap<>();
        base.forEach((pos, spec) -> {
            VariantSet set = catalog.variantOf(spec.id(), entry);
            if (set != null) out.put(pos, set);
        });
        return out;
    }

    /** The old count form as overrides: each set's slots in position order get the counted blocks. */
    public static Map<BlockPos, String> countsToOverrides(Map<BlockPos, VariantSet> slots, Map<String, Map<String, Integer>> counts) {
        Map<BlockPos, String> out = new LinkedHashMap<>();
        counts.forEach((setId, perBlock) -> {
            Iterator<BlockPos> it = slots.entrySet().stream().filter(e -> e.getValue().id().equals(setId)).map(Map.Entry::getKey).iterator();
            perBlock.forEach((block, n) -> {
                for (int i = 0; i < n && it.hasNext(); i++) out.put(it.next(), block);
            });
        });
        return out;
    }

    /** How many blocks of a set the structure has against how many the blueprint has (only for sets sharing a family). */
    public record Check(VariantSet set, int required, int actual) {
        public boolean ok() {
            return required == actual;
        }
    }

    public static List<Check> check(Catalog catalog, String entry, Map<BlockPos, VariantSet> slots, Map<BlockPos, BlockSpec> result) {
        Map<VariantSet, int[]> counts = new LinkedHashMap<>();
        slots.forEach((pos, set) -> {
            if (set.family().isEmpty()) return;
            counts.computeIfAbsent(set, k -> new int[2])[0]++;
            BlockSpec now = result.get(pos);
            if (now == null) return;
            for (VariantSet f : catalog.family(set, entry))
                if (f.blocks().contains(now.id())) {
                    counts.computeIfAbsent(f, k -> new int[2])[1]++;
                    break;
                }
        });
        List<Check> out = new ArrayList<>();
        counts.forEach((set, c) -> out.add(new Check(set, c[0], c[1])));
        return out;
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

    /** A block ready to put in the world. */
    public record Placed(BlockState state, @Nullable net.minecraft.nbt.CompoundTag nbt) {
    }

    /** The blueprint's bounds. */
    public record Footprint(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public static Footprint of(Collection<BlockPos> blocks) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : blocks) {
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            }
            return new Footprint(minX, minY, minZ, maxX, maxY, maxZ);
        }

        /** x/z extents after rotating and centering (as world() lays it out): {minX, maxX, minZ, maxZ} relative to the target. */
        int[] centered(Rotation rot) {
            BlockPos a = new BlockPos(minX, 0, minZ).rotate(rot), b = new BlockPos(maxX, 0, maxZ).rotate(rot);
            int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX()), z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
            int ox = -Math.floorDiv(x0 + x1, 2), oz = -Math.floorDiv(z0 + z1, 2);
            return new int[]{x0 + ox, x1 + ox, z0 + oz, z1 + oz};
        }
    }

    /** The target (bottom centre) that puts a structure's near face on `front`, extending away in `facing`. */
    public static BlockPos centerFor(Footprint fp, BlockPos front, Direction facing) {
        int[] e = fp.centered(rotationFor(facing));
        return switch (facing) {
            case NORTH -> front.offset(0, 0, -e[3]);
            case SOUTH -> front.offset(0, 0, -e[2]);
            case EAST -> front.offset(-e[0], 0, 0);
            default -> front.offset(-e[1], 0, 0);
        };
    }

    /** World blocks: rotated by facing and centred on target (the bottom centre), so turning a structure keeps it in place. */
    public static Map<BlockPos, Placed> world(Map<BlockPos, BlockSpec> specs, BlockPos target, Direction facing) {
        Rotation rot = rotationFor(facing);
        var at = layout(Footprint.of(specs.keySet()), target, facing);
        Map<BlockPos, Placed> out = new TreeMap<>();
        specs.forEach((p, spec) -> {
            BlockState state = state(spec);
            if (state != null) out.put(at.apply(p), new Placed(state.rotate(rot), spec.nbt()));
        });
        return out;
    }

    /** Blueprint position -> world position for a structure with this footprint centred on target and facing this way. */
    public static java.util.function.Function<BlockPos, BlockPos> layout(Footprint fp, BlockPos target, Direction facing) {
        Rotation rot = rotationFor(facing);
        BlockPos a = new BlockPos(fp.minX(), 0, fp.minZ()).rotate(rot), b = new BlockPos(fp.maxX(), 0, fp.maxZ()).rotate(rot);
        BlockPos offset = target.offset(-Math.floorDiv(a.getX() + b.getX(), 2), -fp.minY(), -Math.floorDiv(a.getZ() + b.getZ(), 2));
        return p -> p.rotate(rot).offset(offset);
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
