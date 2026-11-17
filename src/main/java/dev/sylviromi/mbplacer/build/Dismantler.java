package dev.sylviromi.mbplacer.build;

import dev.sylviromi.mbplacer.MbPlacer;
import dev.sylviromi.mbplacer.ae2.AeLink;
import dev.sylviromi.mbplacer.item.PlacerItem;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Removes the blocks of the selected structure at a placement and gives their drops back: ME first, then inventory, then the floor. */
public final class Dismantler {
    private Dismantler() {
    }

    public static void dismantle(ServerPlayer player, ItemStack tool, BlockPos target, Direction facing) {
        Selection sel = PlacerItem.selection(tool);
        Map<BlockPos, BlockSpec> specs = sel.isEmpty() ? null : Resolver.specs(Catalog.server, sel);
        if (specs == null || specs.isEmpty()) {
            player.displayClientMessage(Component.translatable("mbplacer.msg.nothing_selected").withStyle(ChatFormatting.RED), true);
            return;
        }
        ServerLevel level = player.serverLevel();
        List<ItemStack> drops = new ArrayList<>();
        int removed = 0;
        // top-down, so blocks resting on others (torches, rotors) go first
        var blocks = new ArrayList<>(Resolver.world(specs, target, facing).entrySet());
        blocks.sort(Comparator.comparingInt((Map.Entry<BlockPos, Resolver.Placed> e) -> e.getKey().getY()).reversed());
        for (var e : blocks) {
            BlockPos pos = e.getKey();
            BlockState state = level.getBlockState(pos);
            // only blocks that belong to the structure; anything else in the area is left alone
            if (state.getBlock() != e.getValue().state().getBlock() || !level.isLoaded(pos) || !level.mayInteract(player, pos)
                    || state.getDestroySpeed(level, pos) < 0 || MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player)))
                continue;
            if (!player.isCreative()) drops.addAll(Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, ItemStack.EMPTY));
            level.removeBlock(pos, false);
            removed++;
        }
        for (ItemStack stack : drops) {
            if (MbPlacer.AE2) stack.setCount((int) (stack.getCount() - AeLink.insert(player, tool, stack)));
            if (!stack.isEmpty() && !player.getInventory().add(stack)) player.drop(stack, false);
        }
        player.displayClientMessage(removed == 0 ? Component.translatable("mbplacer.msg.nothing_to_dismantle").withStyle(ChatFormatting.RED)
                : Component.translatable("mbplacer.msg.dismantled", removed), true);
    }
}
