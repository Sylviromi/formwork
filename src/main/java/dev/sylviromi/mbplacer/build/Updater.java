package dev.sylviromi.mbplacer.build;

import dev.sylviromi.mbplacer.structure.BlockSpec;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.PacketDistributor;
import dev.sylviromi.mbplacer.net.Net;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Changes a placed multiblock to another selection (variants, tier, settings) in place:
 * only blocks that differ are swapped, the new ones are paid for, the old ones come back.
 */
public final class Updater {
    private Updater() {
    }

    /** remove: placed blocks that go away or change; place: blocks the new selection needs that are not there yet. */
    public record Plan(Map<BlockPos, BlockState> remove, Map<BlockPos, Resolver.Placed> place, Map<BlockPos, Resolver.Placed> all) {
    }

    @Nullable
    public static Plan plan(ServerLevel level, Placements.Rec rec, Selection sel) {
        Map<BlockPos, Resolver.Placed> old = Dismantler.blocks(Catalog.server, rec);
        Map<BlockPos, BlockSpec> specs = Resolver.specs(Catalog.server, sel);
        if (old == null || specs == null || specs.isEmpty()) return null;
        Map<BlockPos, Resolver.Placed> neu = Resolver.world(specs, rec.target(), rec.facing());
        Map<BlockPos, BlockState> remove = new LinkedHashMap<>();
        old.forEach((pos, b) -> {
            BlockState world = level.getBlockState(pos);
            Resolver.Placed want = neu.get(pos);
            if (world.getBlock() == b.state().getBlock() && (want == null || want.state().getBlock() != world.getBlock())) remove.put(pos, world);
        });
        Map<BlockPos, Resolver.Placed> place = new TreeMap<>();
        neu.forEach((pos, b) -> {
            if (level.getBlockState(pos).getBlock() != b.state().getBlock()) place.put(pos, b);
        });
        return new Plan(remove, place, neu);
    }

    /** What the change costs: only the blocks that get placed. */
    public static List<Materials.Row> report(ServerPlayer player, ItemStack tool, Plan plan) {
        return Materials.report(player, tool, Materials.needed(plan.place().values()));
    }

    public static void apply(ServerPlayer player, ItemStack tool, UUID recId, Selection sel) {
        ServerLevel level = player.serverLevel();
        Placements placements = Placements.of(level);
        Placements.Rec rec = placements.all().stream().filter(r -> r.id().equals(recId)).findFirst().orElse(null);
        if (JOBS.stream().anyMatch(j -> j.rec().id().equals(recId))) return; // already being updated
        if (rec == null || !rec.box().getCenter().closerThan(player.blockPosition(), 64)) {
            fail(player, "mbplacer.msg.not_placed");
            return;
        }
        Plan plan = plan(level, rec, sel);
        if (plan == null) {
            fail(player, "mbplacer.msg.invalid_selection");
            return;
        }
        for (var e : plan.remove().entrySet())
            if (!Dismantler.canTake(player, level, e.getKey(), e.getValue())) {
                fail(player, "mbplacer.msg.blocked", e.getKey().getX(), e.getKey().getY(), e.getKey().getZ());
                return;
            }
        for (BlockPos pos : plan.place().keySet()) {
            boolean free = plan.remove().containsKey(pos) || level.getBlockState(pos).canBeReplaced();
            if (!free || !level.isLoaded(pos) || !level.mayInteract(player, pos)
                    || ForgeEventFactory.onBlockPlace(player, BlockSnapshot.create(level.dimension(), level, pos), Direction.UP)) {
                fail(player, "mbplacer.msg.blocked", pos.getX(), pos.getY(), pos.getZ());
                return;
            }
        }
        if (!player.isCreative()) {
            List<Materials.Row> rows = report(player, tool, plan);
            List<Materials.Row> missing = rows.stream().filter(r -> r.missing() > 0).toList();
            if (!missing.isEmpty()) {
                fail(player, "mbplacer.msg.missing", missing.stream().limit(3).map(r -> r.missing() + "x " + r.item().getDescription().getString())
                        .collect(Collectors.joining(", ")), missing.size());
                return;
            }
            if (!Materials.take(player, tool, rows)) {
                fail(player, "mbplacer.msg.me_changed");
                return;
            }
        }
        // everything is checked and paid for: swap the blocks bottom-up over a few seconds (see onServerTick)
        TreeSet<BlockPos> order = new TreeSet<>(Comparator.comparingInt((BlockPos p) -> p.getY()).thenComparingLong(BlockPos::asLong));
        order.addAll(plan.remove().keySet());
        order.addAll(plan.place().keySet());
        int ticks = Placements.dismantleTicks(rec);
        JOBS.add(new Job(player, tool, level, rec, sel, plan, new ArrayList<>(order), new ArrayList<>(), ticks, new int[2]));
        Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Net.Progress(rec.id(), 0));
    }

    /** A running update. done[0]: positions handled, done[1]: ticks elapsed. */
    private record Job(ServerPlayer player, ItemStack tool, ServerLevel level, Placements.Rec rec, Selection sel, Plan plan,
                       List<BlockPos> order, List<ItemStack> drops, int ticks, int[] done) {
    }

    private static final List<Job> JOBS = new ArrayList<>();

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) return;
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            Job j = it.next();
            int t = ++j.done()[1];
            int until = (int) Math.ceil(j.order().size() * Math.min(1.0, (double) t / j.ticks()));
            for (; j.done()[0] < until; j.done()[0]++) {
                BlockPos pos = j.order().get(j.done()[0]);
                if (!j.level().isLoaded(pos)) continue;
                BlockState was = j.plan().remove().get(pos);
                if (was != null && j.level().getBlockState(pos).getBlock() == was.getBlock()) Dismantler.take(j.player(), j.level(), pos, was, j.drops());
                Resolver.Placed want = j.plan().place().get(pos);
                if (want != null) Placer.put(j.level(), Map.of(pos, want), j.player());
            }
            if (!j.player().hasDisconnected())
                Net.CHANNEL.send(PacketDistributor.PLAYER.with(j::player), new Net.Progress(j.rec().id(), Math.min(0.99f, (float) j.done()[0] / j.order().size())));
            if (j.done()[0] < j.order().size()) continue;
            it.remove();
            if (j.player().hasDisconnected()) j.drops().forEach(s -> Block.popResource(j.level(), j.rec().target(), s));
            else Dismantler.giveBack(j.player(), j.tool(), j.drops());
            Placements placements = Placements.of(j.level());
            placements.remove(j.level(), j.rec().id());
            placements.add(j.level(), Placements.record(j.plan().all(), j.sel().save(), j.rec().target(), j.rec().facing(), j.rec().owner()));
            if (!j.player().hasDisconnected()) Net.CHANNEL.send(PacketDistributor.PLAYER.with(j::player), new Net.Progress(j.rec().id(), 1));
            j.player().displayClientMessage(Component.translatable("mbplacer.msg.updated", j.plan().remove().size(), j.plan().place().size()), true);
        }
    }

    private static void fail(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args).withStyle(ChatFormatting.RED), true);
    }
}
