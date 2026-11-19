package dev.sylviromi.formwork.build;

import dev.sylviromi.formwork.net.Net;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Every multiblock the tool placed in one dimension, so it can be dismantled by pointing at it. Synced to the players in that dimension. */
public final class Placements extends SavedData {
    private static final String NAME = "formwork_placements";

    /** selection: Selection.save() of what was placed; box: the world blocks' bounds. */
    public record Rec(UUID id, CompoundTag selection, BlockPos target, Direction facing, BoundingBox box, @Nullable UUID owner) {
        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.put("selection", selection);
            t.putLong("target", target.asLong());
            t.putInt("facing", facing.get2DDataValue());
            t.putIntArray("box", new int[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
            if (owner != null) t.putUUID("owner", owner);
            return t;
        }

        public static Rec load(CompoundTag t) {
            int[] b = t.getIntArray("box");
            return new Rec(t.getUUID("id"), t.getCompound("selection"), BlockPos.of(t.getLong("target")), Direction.from2DDataValue(t.getInt("facing")),
                    new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]), t.hasUUID("owner") ? t.getUUID("owner") : null);
        }
    }

    private final Map<UUID, Rec> recs = new LinkedHashMap<>();

    public static Placements of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Placements::load, Placements::new, NAME);
    }

    private static Placements load(CompoundTag tag) {
        Placements p = new Placements();
        for (Tag t : tag.getList("placements", Tag.TAG_COMPOUND)) {
            Rec r = Rec.load((CompoundTag) t);
            p.recs.put(r.id(), r);
        }
        return p;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        recs.values().forEach(r -> list.add(r.save()));
        tag.put("placements", list);
        return tag;
    }

    public Collection<Rec> all() {
        return recs.values();
    }

    /** The newest placement whose bounds contain pos. */
    @Nullable
    public static Rec at(Collection<Rec> recs, BlockPos pos) {
        Rec found = null;
        for (Rec r : recs) if (r.box().isInside(pos)) found = r;
        return found;
    }

    /** How long dismantling (holding use) or an in-place update takes: 0.5 to 2 seconds by size. */
    public static int dismantleTicks(Rec r) {
        long volume = (long) r.box().getXSpan() * r.box().getYSpan() * r.box().getZSpan();
        return (int) Math.max(10, Math.min(40, 10 + volume / 40));
    }

    private static final Map<Rec, Set<BlockPos>> POSITIONS = Collections.synchronizedMap(new WeakHashMap<>());

    /** The world positions of a placement's blocks (cached per record). */
    public static Set<BlockPos> positions(Rec r, dev.sylviromi.formwork.structure.Catalog catalog) {
        return POSITIONS.computeIfAbsent(r, k -> {
            var blocks = Dismantler.blocks(catalog, k);
            return blocks == null ? Set.of() : Set.copyOf(blocks.keySet());
        });
    }

    public void add(ServerLevel level, Rec r) {
        recs.put(r.id(), r);
        changed(level);
    }

    public void remove(ServerLevel level, UUID id) {
        if (recs.remove(id) != null) changed(level);
    }

    private void changed(ServerLevel level) {
        setDirty();
        Net.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension), new Net.PlacementsSync(List.copyOf(recs.values())));
    }

    public static Rec record(Map<BlockPos, ?> blocks, CompoundTag selection, BlockPos target, Direction facing, @Nullable UUID owner) {
        return new Rec(UUID.randomUUID(), selection, target, facing, BoundingBox.encapsulatingPositions(blocks.keySet()).orElseThrow(), owner);
    }
}
