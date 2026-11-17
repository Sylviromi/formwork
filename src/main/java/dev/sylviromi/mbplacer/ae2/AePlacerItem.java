package dev.sylviromi.mbplacer.ae2;

import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import dev.sylviromi.mbplacer.item.PlacerItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** The tool when AE2 is loaded: AE2 opens its craft screens through it (see PlacerMenuHost). Only loaded when AE2 is present. */
final class AePlacerItem extends PlacerItem implements IMenuItem {
    @Override
    public ItemMenuHost getMenuHost(Player player, int inventorySlot, ItemStack stack, @Nullable BlockPos pos) {
        return new PlacerMenuHost(player, inventorySlot, stack);
    }
}
