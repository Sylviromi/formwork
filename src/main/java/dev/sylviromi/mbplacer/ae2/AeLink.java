package dev.sylviromi.mbplacer.ae2;

import appeng.api.config.Actionable;
import appeng.api.features.GridLinkables;
import appeng.api.features.IGridLinkableHandler;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.core.definitions.AEItems;
import appeng.helpers.WirelessTerminalMenuHost;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.crafting.CraftAmountMenu;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Everything that touches AE2. Only call into this class after checking that AE2 is loaded.
 * The tool links to a Wireless Access Point like a wireless terminal: the WAP stores its position on the stack.
 */
public final class AeLink {
    private static final String TAG = "accessPoint";

    private AeLink() {
    }

    public static void register(Item tool) {
        GridLinkables.register(tool, new IGridLinkableHandler() {
            @Override
            public boolean canLink(ItemStack stack) {
                return stack.is(tool);
            }

            @Override
            public void link(ItemStack stack, GlobalPos pos) {
                GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, pos).result().ifPresent(t -> stack.getOrCreateTag().put(TAG, t));
            }

            @Override
            public void unlink(ItemStack stack) {
                stack.removeTagKey(TAG);
            }
        });
    }

    public static boolean isLinked(ItemStack stack) {
        return stack.getTag() != null && stack.getTag().contains(TAG);
    }

    /** The linked grid if the player is inside the range of one of its active access points, else null. */
    @Nullable
    public static IGrid grid(ServerPlayer player, ItemStack tool) {
        IGridNode node = node(player, tool);
        return node == null ? null : node.getGrid();
    }

    /**
     * The grid node of an access point of the linked network with the player in range, or null. The range check is AE2's own
     * wireless terminal check, so boosters and add-ons that extend it (AEInfinityBooster's cards) apply to the tool as well.
     */
    @Nullable
    public static IGridNode node(ServerPlayer player, ItemStack tool) {
        if (!isLinked(tool)) return null;
        GlobalPos pos = GlobalPos.CODEC.parse(NbtOps.INSTANCE, tool.getTag().get(TAG)).result().orElse(null);
        if (pos == null) return null;
        // check the link first: AE2's lookup tells the player off when the network is gone, which we poll for
        ServerLevel level = player.server.getLevel(pos.dimension());
        if (level == null || !level.isLoaded(pos.pos()) || !(level.getBlockEntity(pos.pos()) instanceof IWirelessAccessPoint linked) || linked.getGrid() == null)
            return null;
        ItemStack terminal = AEItems.WIRELESS_TERMINAL.stack();
        terminal.getOrCreateTag().put(TAG, tool.getTag().get(TAG).copy());
        return new WirelessTerminalMenuHost(player, null, terminal, (p, m) -> {}).getActionableNode();
    }

    /** Stored amount and craftability for each item, or null when the tool has no reachable grid. */
    @Nullable
    public static Map<Item, long[]> snapshot(ServerPlayer player, ItemStack tool, Iterable<Item> items) {
        IGrid grid = grid(player, tool);
        if (grid == null) return null;
        Map<Item, long[]> out = new java.util.HashMap<>();
        for (Item item : items) out.put(item, new long[]{stored(grid, item), craftable(grid, item) ? 1 : 0});
        return out;
    }

    /** Takes all items from the grid or none: anything already extracted is put back when one item falls short. */
    public static boolean take(ServerPlayer player, ItemStack tool, Map<Item, Long> amounts) {
        if (amounts.isEmpty()) return true;
        IGrid grid = grid(player, tool);
        if (grid == null) return false;
        IActionSource src = IActionSource.ofPlayer(player);
        Map<Item, Long> taken = new java.util.HashMap<>();
        for (var e : amounts.entrySet()) {
            long got = extract(grid, e.getKey(), e.getValue(), player);
            taken.put(e.getKey(), got);
            if (got < e.getValue()) {
                taken.forEach((item, n) -> grid.getStorageService().getInventory().insert(AEItemKey.of(item), n, Actionable.MODULATE, src));
                return false;
            }
        }
        return true;
    }

    /** Puts the stack into the tool's grid; returns how many were stored (0 without a reachable grid). */
    public static long insert(ServerPlayer player, ItemStack tool, ItemStack stack) {
        IGrid grid = grid(player, tool);
        AEItemKey key = AEItemKey.of(stack);
        if (grid == null || key == null) return 0;
        return grid.getStorageService().getInventory().insert(key, stack.getCount(), Actionable.MODULATE, IActionSource.ofPlayer(player));
    }

    public static long stored(IGrid grid, Item item) {
        return grid.getStorageService().getCachedInventory().get(AEItemKey.of(item));
    }

    public static boolean craftable(IGrid grid, Item item) {
        return grid.getCraftingService().isCraftable(AEItemKey.of(item));
    }

    public static long extract(IGrid grid, Item item, long amount, ServerPlayer player) {
        return grid.getStorageService().getInventory().extract(AEItemKey.of(item), amount, Actionable.MODULATE, IActionSource.ofPlayer(player));
    }

    /** Opens AE2's own craft screen (amount, plan, start) for this item on the tool's grid, as a wireless terminal would. */
    public static void openCraft(ServerPlayer player, InteractionHand hand, Item item, int amount) {
        if (node(player, player.getItemInHand(hand)) == null) return;
        CraftAmountMenu.open(player, MenuLocators.forHand(player, hand), AEItemKey.of(item), Math.max(1, amount));
    }

    /** The tool as registered when AE2 is present: it can host AE2's craft screens. */
    public static Item placerItem() {
        return new AePlacerItem();
    }
}
