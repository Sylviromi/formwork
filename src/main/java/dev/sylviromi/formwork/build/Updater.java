package dev.sylviromi.formwork.build;

import dev.sylviromi.formwork.structure.BlockSpec;
import dev.sylviromi.formwork.structure.Catalog;
import dev.sylviromi.formwork.structure.Selection;
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
import dev.sylviromi.formwork.net.Net;
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
        Map<BlockPos, Resolver.Placed> neu = Resolver.world(specs, rec.target(), rec.facing(), rec.mirror());
        neu.keySet().removeAll(rec.skipped()); // what was in the way at placement stays untouched
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

    /** Starts an update: checked now, carried out all at once after the same wait as a dismantle; use with the tool cancels it. */
    public static void apply(ServerPlayer player, ItemStack tool, UUID recId, Selection sel) {
        if (JOBS.stream().anyMatch(j -> j.rec().id().equals(recId) || j.player() == player)) return; // already waiting
        Placements.Rec rec = Placements.of(player.serverLevel()).all().stream().filter(r -> r.id().equals(recId)).findFirst().orElse(null);
        if (check(player, tool, rec, sel) == null) return;
        int ticks = Placements.dismantleTicks(rec);
        JOBS.add(new Job(player, tool, player.serverLevel(), rec, sel, ticks, new int[1]));
        progress(player, rec.id(), ticks);
        player.displayClientMessage(Component.translatable("formwork.msg.update_waiting"), true);
    }

    public static boolean waiting(ServerPlayer player) {
        return JOBS.stream().anyMatch(j -> j.player() == player);
    }

    /** Cancels the player's waiting update; false if there is none. */
    public static boolean cancel(ServerPlayer player) {
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            Job j = it.next();
            if (j.player() != player) continue;
            it.remove();
            progress(player, j.rec().id(), 0);
            player.displayClientMessage(Component.translatable("formwork.msg.update_cancelled"), true);
            return true;
        }
        return false;
    }

    /** The plan if the update can go ahead now (in reach, not blocked, materials there), else null after telling the player why. */
    @Nullable
    private static Plan check(ServerPlayer player, ItemStack tool, @Nullable Placements.Rec rec, Selection sel) {
        ServerLevel level = player.serverLevel();
        if (rec == null || !Placements.of(level).all().contains(rec) || !rec.box().getCenter().closerThan(player.blockPosition(), 64)) {
            fail(player, "formwork.msg.not_placed");
            return null;
        }
        Plan plan = plan(level, rec, sel);
        if (plan == null) {
            fail(player, "formwork.msg.invalid_selection");
            return null;
        }
        for (var e : plan.remove().entrySet())
            if (!Dismantler.canTake(player, level, e.getKey(), e.getValue())) {
                fail(player, "formwork.msg.blocked", e.getKey().getX(), e.getKey().getY(), e.getKey().getZ());
                return null;
            }
        for (BlockPos pos : plan.place().keySet()) {
            boolean free = plan.remove().containsKey(pos) || level.getBlockState(pos).canBeReplaced();
            if (!free || !level.isLoaded(pos) || !level.mayInteract(player, pos)
                    || ForgeEventFactory.onBlockPlace(player, BlockSnapshot.create(level.dimension(), level, pos), Direction.UP)) {
                fail(player, "formwork.msg.blocked", pos.getX(), pos.getY(), pos.getZ());
                return null;
            }
        }
        if (!player.isCreative()) {
            List<Materials.Row> missing = report(player, tool, plan).stream().filter(r -> r.missing() > 0).toList();
            if (!missing.isEmpty()) {
                fail(player, "formwork.msg.missing", missing.stream().limit(3).map(r -> r.missing() + "x " + r.item().getDescription().getString())
                        .collect(Collectors.joining(", ")), missing.size());
                return null;
            }
        }
        return plan;
    }

    /** Checks again, pays, and swaps every changed block at once. */
    private static void perform(Job j) {
        ServerPlayer player = j.player();
        Plan plan = check(player, j.tool(), j.rec(), j.sel());
        if (plan == null) return;
        if (!player.isCreative() && !Materials.take(player, j.tool(), report(player, j.tool(), plan))) {
            fail(player, "formwork.msg.me_changed");
            return;
        }
        List<ItemStack> drops = new ArrayList<>();
        plan.remove().forEach((pos, was) -> {
            if (j.level().getBlockState(pos).getBlock() == was.getBlock()) Dismantler.take(player, j.level(), pos, was, drops);
        });
        Placer.put(j.level(), plan.place(), player);
        Dismantler.giveBack(player, j.tool(), drops);
        Placements placements = Placements.of(j.level());
        placements.remove(j.level(), j.rec().id());
        placements.add(j.level(), Placements.record(plan.all(), j.sel().save(), j.rec().target(), j.rec().facing(), j.rec().mirror(), j.rec().owner(), j.rec().skipped()));
        player.displayClientMessage(Component.translatable("formwork.msg.updated", plan.remove().size(), plan.place().size()), true);
    }

    private static void progress(ServerPlayer player, UUID rec, int ticks) {
        if (!player.hasDisconnected()) Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Net.Progress(rec, ticks));
    }

    /** An update waiting to happen. waited[0]: ticks so far. */
    private record Job(ServerPlayer player, ItemStack tool, ServerLevel level, Placements.Rec rec, Selection sel, int ticks, int[] waited) {
    }

    private static final List<Job> JOBS = new ArrayList<>();

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) return;
        for (Iterator<Job> it = new ArrayList<>(JOBS).iterator(); it.hasNext(); ) {
            Job j = it.next();
            if (j.player().hasDisconnected()) {
                JOBS.remove(j);
                continue;
            }
            if (++j.waited()[0] < j.ticks()) continue;
            JOBS.remove(j);
            perform(j);
            progress(j.player(), j.rec().id(), 0);
        }
    }

    private static void fail(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args).withStyle(ChatFormatting.RED), true);
    }
}
