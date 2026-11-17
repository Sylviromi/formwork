package dev.sylviromi.mbplacer.ae2;

import appeng.api.config.Actionable;
import appeng.api.features.GridLinkables;
import appeng.api.features.IGridLinkableHandler;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

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
        if (!isLinked(tool)) return null;
        GlobalPos pos = GlobalPos.CODEC.parse(NbtOps.INSTANCE, tool.getTag().get(TAG)).result().orElse(null);
        if (pos == null) return null;
        ServerLevel level = player.server.getLevel(pos.dimension());
        if (level == null || !level.isLoaded(pos.pos())) return null;
        BlockEntity be = level.getBlockEntity(pos.pos());
        if (!(be instanceof IWirelessAccessPoint linked) || linked.getGrid() == null) return null;
        IGrid grid = linked.getGrid();
        for (Class<?> c : grid.getMachineClasses()) {
            if (!IWirelessAccessPoint.class.isAssignableFrom(c)) continue;
            for (Object o : grid.getActiveMachines(c)) {
                IWirelessAccessPoint wap = (IWirelessAccessPoint) o;
                var loc = wap.getLocation();
                if (!wap.isActive() || loc.getLevel() != player.level()) continue;
                double range = wap.getRange();
                if (loc.getPos().getCenter().distanceToSqr(player.position()) <= range * range) return grid;
            }
        }
        return null;
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

    /** Requests crafts for the given items on the tool's grid. */
    public static boolean requestCrafts(ServerPlayer player, ItemStack tool, Map<Item, Long> missing) {
        IGrid grid = grid(player, tool);
        if (grid == null || missing.isEmpty()) return false;
        requestCrafts(player, grid, missing);
        return true;
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

    private record Pending(ServerPlayer player, IGrid grid, Item item, Future<ICraftingPlan> plan) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    /** Starts crafting calculations; jobs are submitted from the server tick once each plan is ready. */
    public static void requestCrafts(ServerPlayer player, IGrid grid, Map<Item, Long> missing) {
        ICraftingSimulationRequester requester = () -> IActionSource.ofPlayer(player);
        missing.forEach((item, amount) -> PENDING.add(new Pending(player, grid, item,
                grid.getCraftingService().beginCraftingCalculation(player.level(), requester, AEItemKey.of(item), amount, CalculationStrategy.REPORT_MISSING_ITEMS))));
        player.displayClientMessage(Component.translatable("mbplacer.msg.crafting_started", missing.size()), true);
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!p.plan().isDone()) continue;
            it.remove();
            Component name = p.item().getDescription();
            try {
                ICraftingPlan plan = p.plan().get();
                if (plan.simulation()) {
                    p.player().sendSystemMessage(Component.translatable("mbplacer.msg.craft_missing", name));
                    continue;
                }
                var result = p.grid().getCraftingService().submitJob(plan, null, null, false, IActionSource.ofPlayer(p.player()));
                if (!result.successful())
                    p.player().sendSystemMessage(Component.translatable("mbplacer.msg.craft_failed", name, String.valueOf(result.errorCode())));
            } catch (Exception e) {
                p.player().sendSystemMessage(Component.translatable("mbplacer.msg.craft_failed", name, e.getMessage()));
            }
        }
    }
}
