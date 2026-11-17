package dev.sylviromi.mbplacer.build;

import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.structure.BlockSpec;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Server side placement. Everything is checked before anything is taken or placed. */
public final class Placer {
    private Placer() {
    }

    public static void place(ServerPlayer player, ItemStack tool, BlockPos target, Direction facing) {
        ServerLevel level = player.serverLevel();
        Selection sel = PlacerItem.selection(tool);
        if (sel.isEmpty()) {
            fail(player, "mbplacer.msg.nothing_selected");
            return;
        }
        Map<BlockPos, BlockSpec> specs = Resolver.specs(Catalog.server, sel);
        if (specs == null || specs.isEmpty()) {
            fail(player, "mbplacer.msg.invalid_selection");
            return;
        }
        Map<BlockPos, Resolver.Placed> blocks = Resolver.world(specs, target, facing);
        for (BlockPos pos : blocks.keySet()) {
            boolean ok = level.isInWorldBounds(pos) && level.isLoaded(pos) && level.getBlockState(pos).canBeReplaced()
                    && level.mayInteract(player, pos)
                    && !ForgeEventFactory.onBlockPlace(player, BlockSnapshot.create(level.dimension(), level, pos), Direction.UP);
            if (!ok) {
                fail(player, "mbplacer.msg.blocked", pos.getX(), pos.getY(), pos.getZ());
                return;
            }
        }
        if (!player.isCreative()) {
            var needed = Materials.needed(blocks.values());
            List<Materials.Row> rows = Materials.report(player, tool, needed);
            List<Materials.Row> missing = rows.stream().filter(r -> r.missing() > 0).toList();
            if (!missing.isEmpty()) {
                String list = missing.stream().limit(3).map(r -> r.missing() + "x " + r.item().getDescription().getString())
                        .collect(Collectors.joining(", "));
                fail(player, "mbplacer.msg.missing", list, missing.size());
                return;
            }
            if (!Materials.take(player, tool, rows)) {
                fail(player, "mbplacer.msg.me_changed");
                return;
            }
        }
        put(level, blocks, player);
        player.displayClientMessage(Component.translatable("mbplacer.msg.placed", blocks.size()), true);
    }

    /** Puts the blocks in the world, bottom-up, and loads block entity data. Player may be null (commands). */
    public static void put(ServerLevel level, Map<BlockPos, Resolver.Placed> blocks, @Nullable ServerPlayer player) {
        blocks.forEach((pos, b) -> {
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
        });
    }

    private static void fail(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args).withStyle(net.minecraft.ChatFormatting.RED), true);
    }
}
