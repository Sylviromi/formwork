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
        List<ItemStack> drops = new ArrayList<>();
        plan.remove().forEach((pos, state) -> Dismantler.take(player, level, pos, state, drops));
        Placer.put(level, plan.place(), player);
        Dismantler.giveBack(player, tool, drops);
        placements.remove(level, rec.id());
        placements.add(level, Placements.record(plan.all(), sel.save(), rec.target(), rec.facing(), rec.owner()));
        player.displayClientMessage(Component.translatable("mbplacer.msg.updated", plan.remove().size(), plan.place().size()), true);
    }

    private static void fail(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args).withStyle(ChatFormatting.RED), true);
    }
}
