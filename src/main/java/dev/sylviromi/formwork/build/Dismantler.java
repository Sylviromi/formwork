package dev.sylviromi.formwork.build;

import dev.sylviromi.formwork.net.Net;
import dev.sylviromi.formwork.Formwork;
import dev.sylviromi.formwork.ae2.AeLink;
import dev.sylviromi.formwork.structure.BlockSpec;
import dev.sylviromi.formwork.structure.Catalog;
import dev.sylviromi.formwork.structure.Selection;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Removes a multiblock the tool placed (found by pointing at any block inside it) and gives the drops back:
 * ME first, then inventory, then the floor. Only blocks that still match the placement are removed.
 */
public final class Dismantler {
    private Dismantler() {
    }

    /** The placed blocks of a record (without its skipped spots), or null when its entry is no longer in the catalog. */
    @Nullable
    public static Map<BlockPos, Resolver.Placed> blocks(Catalog catalog, Placements.Rec rec) {
        Map<BlockPos, BlockSpec> specs = Resolver.specs(catalog, Selection.load(rec.selection()));
        if (specs == null) return null;
        Map<BlockPos, Resolver.Placed> out = Resolver.world(specs, rec.target(), rec.facing(), rec.mirror());
        out.keySet().removeAll(rec.skipped());
        return out;
    }

    public static void dismantle(ServerPlayer player, ItemStack tool, Placements.Rec rec) {
        ServerLevel level = player.serverLevel();
        Placements placements = Placements.of(level);
        Map<BlockPos, Resolver.Placed> placed = blocks(Catalog.server, rec);
        if (placed == null) {
            fail(player, "formwork.msg.invalid_selection");
            return;
        }
        // top-down, so blocks resting on others (torches, rotors) go first
        var order = new ArrayList<>(placed.entrySet());
        order.sort(Comparator.comparingInt((Map.Entry<BlockPos, Resolver.Placed> e) -> e.getKey().getY()).reversed());
        List<BlockPos> take = new ArrayList<>();
        int kept = 0;
        for (var e : order) {
            BlockState state = level.getBlockState(e.getKey());
            if (state.getBlock() != e.getValue().state().getBlock()) continue; // replaced or removed by hand: not ours any more
            if (canTake(player, level, e.getKey(), state)) take.add(e.getKey());
            else kept++;
        }
        // keep the record while protected blocks remain, so the player can come back
        if (kept == 0) placements.remove(level, rec.id());
        List<ItemStack> drops = new ArrayList<>();
        int[] removed = new int[1];
        java.util.Set<BlockPos> wholeSet = new java.util.HashSet<>(take);
        UUID id = player.getUUID();
        Animator.run(take, false, 0, pos -> {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() != placed.get(pos).state().getBlock()) return;
            ServerPlayer who = level.getServer().getPlayerList().getPlayer(id);
            if (who == null) return;
            take(who, level, pos, state, drops);
            removed[0]++;
        }, batch -> {
            Net.pop(level, false, batch, wholeSet, level::getBlockState);
        }, () -> true, () -> {
            ServerPlayer who = level.getServer().getPlayerList().getPlayer(id);
            if (who == null) {
                drops.forEach(d -> Block.popResource(level, take.get(take.size() - 1), d)); // left the game meanwhile
                return;
            }
            giveBack(who, tool, drops);
            who.displayClientMessage(Component.translatable("formwork.msg.dismantled", removed[0]), true);
        });
    }

    /** Whether the player may break this block here (claims, unbreakable blocks, other mods' break events). */
    public static boolean canTake(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        return level.isLoaded(pos) && level.mayInteract(player, pos) && state.getDestroySpeed(level, pos) >= 0
                && !MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player));
    }

    /** Removes the block and collects its drops (none in creative). Check canTake first. */
    public static void take(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, List<ItemStack> drops) {
        if (!player.isCreative()) drops.addAll(Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, ItemStack.EMPTY));
        level.removeBlock(pos, false);
    }

    /** ME first, then inventory, then the floor. */
    public static void giveBack(ServerPlayer player, ItemStack tool, List<ItemStack> drops) {
        for (ItemStack stack : drops) {
            if (Formwork.AE2) stack.setCount((int) (stack.getCount() - AeLink.insert(player, tool, stack)));
            if (!stack.isEmpty() && !player.getInventory().add(stack)) player.drop(stack, false);
        }
    }

    private static void fail(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
    }
}
