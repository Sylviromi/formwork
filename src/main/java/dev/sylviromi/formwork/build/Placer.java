package dev.sylviromi.formwork.build;

import dev.sylviromi.formwork.item.PlacerItem;
import dev.sylviromi.formwork.net.Net;
import dev.sylviromi.formwork.structure.BlockSpec;
import dev.sylviromi.formwork.structure.Catalog;
import dev.sylviromi.formwork.structure.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** Server side placement. Everything is checked before anything is taken or placed. */
public final class Placer {
    private Placer() {
    }

    /** skipBlocked (Ctrl): spots that are in the way are left out instead of cancelling the whole placement. */
    public static void place(ServerPlayer player, ItemStack tool, BlockPos target, Direction facing, boolean mirror, boolean skipBlocked) {
        ServerLevel level = player.serverLevel();
        Selection sel = PlacerItem.selection(tool);
        if (sel.isEmpty()) {
            fail(player, "formwork.msg.nothing_selected");
            return;
        }
        Map<BlockPos, BlockSpec> specs = Resolver.specs(Catalog.server, sel);
        if (specs == null || specs.isEmpty()) {
            fail(player, "formwork.msg.invalid_selection");
            return;
        }
        Map<BlockPos, Resolver.Placed> all = Resolver.world(specs, target, facing, mirror);
        Map<BlockPos, Resolver.Placed> blocks = new java.util.TreeMap<>(all);
        for (BlockPos pos : all.keySet()) {
            boolean ok = level.isInWorldBounds(pos) && level.isLoaded(pos) && level.getBlockState(pos).canBeReplaced()
                    && level.mayInteract(player, pos)
                    && !ForgeEventFactory.onBlockPlace(player, BlockSnapshot.create(level.dimension(), level, pos), Direction.UP);
            if (ok) continue;
            if (!skipBlocked) {
                fail(player, "formwork.msg.blocked", pos.getX(), pos.getY(), pos.getZ());
                return;
            }
            blocks.remove(pos);
        }
        if (blocks.isEmpty()) {
            fail(player, "formwork.msg.blocked", target.getX(), target.getY(), target.getZ());
            return;
        }
        if (!player.isCreative()) {
            var needed = Materials.needed(blocks.values());
            List<Materials.Row> rows = Materials.report(player, tool, needed);
            List<Materials.Row> missing = rows.stream().filter(r -> r.missing() > 0).toList();
            if (!missing.isEmpty()) {
                String list = missing.stream().limit(3).map(r -> r.missing() + "x " + r.item().getDescription().getString())
                        .collect(Collectors.joining(", "));
                fail(player, "formwork.msg.missing", list, missing.size());
                return;
            }
            if (!Materials.take(player, tool, rows)) {
                fail(player, "formwork.msg.me_changed");
                return;
            }
        }
        Set<BlockPos> skipped = new HashSet<>(all.keySet());
        skipped.removeAll(blocks.keySet());
        Placements.Rec rec = Placements.record(all, sel.save(), target, facing, mirror, player.getUUID(), skipped);
        Placements.of(level).add(level, rec);
        putAnimated(level, blocks, player, () -> Placements.of(level).all().contains(rec));
        // placed: release the anchor (and its nudges) so the next one follows the crosshair again
        if (PlacerItem.anchor(tool) != null) {
            tool.getTag().remove(PlacerItem.ANCHOR);
            tool.getTag().remove(PlacerItem.ANCHOR_FACING);
            tool.getTag().remove(PlacerItem.OFFSET);
        }
        player.displayClientMessage(skipped.isEmpty() ? Component.translatable("formwork.msg.placed", blocks.size())
                : Component.translatable("formwork.msg.placed_skipped", blocks.size(), skipped.size()), true);
    }

    /** Puts the blocks in the world, bottom-up, and loads block entity data. Player may be null (commands). */
    public static void put(ServerLevel level, Map<BlockPos, Resolver.Placed> blocks, @Nullable ServerPlayer player) {
        blocks.forEach((pos, b) -> putOne(level, pos, b, player));
    }

    /** Like put, but as a sweep over a few ticks. Stops if alive turns false (the placement was dismantled meanwhile). */
    public static void putAnimated(ServerLevel level, Map<BlockPos, Resolver.Placed> blocks, ServerPlayer player, BooleanSupplier alive) {
        UUID id = player.getUUID();
        Animator.run(new ArrayList<>(blocks.keySet()), true, Net.GROW_TICKS, pos -> {
            if (level.getBlockState(pos).canBeReplaced()) putOne(level, pos, blocks.get(pos), level.getServer().getPlayerList().getPlayer(id));
        }, batch -> {
            var open = batch.stream().filter(pos -> level.getBlockState(pos).canBeReplaced()).toList();
            if (open.isEmpty()) return;
            var sound = blocks.get(open.get(0)).state().getSoundType().getPlaceSound();
            level.playSound(null, open.get(0), sound, SoundSource.BLOCKS, 0.6f, 1f);
            Net.pop(level, true, open, blocks.keySet(), pos -> blocks.get(pos).state());
        }, alive, () -> {});
    }

    private static void putOne(ServerLevel level, BlockPos pos, Resolver.Placed b, @Nullable ServerPlayer player) {
        level.setBlock(pos, b.state(), 3);
        if (b.nbt() != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                CompoundTag tag = b.nbt().copy();
                tag.putInt("x", pos.getX());
                tag.putInt("y", pos.getY());
                tag.putInt("z", pos.getZ());
                be.load(tag);
                be.setChanged();
            }
        }
        b.state().getBlock().setPlacedBy(level, pos, b.state(), player, new ItemStack(b.state().getBlock().asItem()));
    }

    private static void fail(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args).withStyle(net.minecraft.ChatFormatting.RED), true);
    }
}
