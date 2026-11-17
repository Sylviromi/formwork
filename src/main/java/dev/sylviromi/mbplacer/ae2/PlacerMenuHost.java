package dev.sylviromi.mbplacer.ae2;

import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.storage.ISubMenuHost;
import appeng.menu.ISubMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Lets AE2's craft screens run from the tool: the grid comes from the linked access point in range; leaving the range closes them. */
final class PlacerMenuHost extends ItemMenuHost implements IActionHost, ISubMenuHost {
    PlacerMenuHost(Player player, @Nullable Integer slot, ItemStack stack) {
        super(player, slot, stack);
    }

    @Nullable
    @Override
    public IGridNode getActionableNode() {
        return getPlayer() instanceof ServerPlayer sp ? AeLink.node(sp, getItemStack()) : null;
    }

    @Override
    public boolean onBroadcastChanges(AbstractContainerMenu menu) {
        return super.onBroadcastChanges(menu) && (isClientSide() || getActionableNode() != null);
    }

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        player.closeContainer();
        // back to the editor the craft screen was opened from (AE2 returns here after Start or Back)
        if (player instanceof ServerPlayer sp) dev.sylviromi.mbplacer.net.Net.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp), new dev.sylviromi.mbplacer.net.Net.ReturnToEditor());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return getItemStack();
    }
}
