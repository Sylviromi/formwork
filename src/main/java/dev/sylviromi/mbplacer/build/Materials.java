package dev.sylviromi.mbplacer.build;

import dev.sylviromi.mbplacer.MbPlacer;
import dev.sylviromi.mbplacer.ae2.AeLink;
import dev.sylviromi.mbplacer.structure.BlockSpec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/** Counting what a structure needs and where the player has it. */
public final class Materials {
    private Materials() {
    }

    /** One line of the material list. me = -1 when there is no ME access. */
    public record Row(Item item, int needed, int inventory, long me, boolean craftable) {
        public int missing() {
            return (int) Math.max(0, needed - inventory - Math.max(0, me));
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeId(net.minecraft.core.registries.BuiltInRegistries.ITEM, item);
            buf.writeVarInt(needed).writeVarInt(inventory).writeVarLong(me + 1).writeBoolean(craftable);
        }

        public static Row read(FriendlyByteBuf buf) {
            return new Row(buf.readById(net.minecraft.core.registries.BuiltInRegistries.ITEM), buf.readVarInt(), buf.readVarInt(), buf.readVarLong() - 1, buf.readBoolean());
        }
    }

    /** ME status shown in the GUI. */
    public enum MeStatus {NO_AE2, NOT_LINKED, OUT_OF_RANGE, CONNECTED}

    // shortcut: blocks without an item (fluids, technical blocks) are placed for free; charge buckets if a pack needs it.
    public static Map<Item, Integer> needed(Collection<BlockState> states) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (BlockState s : states) {
            Item item = s.getBlock().asItem();
            if (item != Items.AIR) out.merge(item, 1, Integer::sum);
        }
        return out;
    }

    public static Map<Item, Integer> neededForSpecs(Collection<BlockSpec> specs) {
        List<BlockState> states = new ArrayList<>();
        for (BlockSpec s : specs) {
            BlockState state = Resolver.state(s);
            if (state != null) states.add(state);
        }
        return needed(states);
    }

    public static int inInventory(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    public static MeStatus meStatus(ServerPlayer player, ItemStack tool) {
        if (!MbPlacer.AE2) return MeStatus.NO_AE2;
        if (!AeLink.isLinked(tool)) return MeStatus.NOT_LINKED;
        return AeLink.grid(player, tool) == null ? MeStatus.OUT_OF_RANGE : MeStatus.CONNECTED;
    }

    public static List<Row> report(ServerPlayer player, ItemStack tool, Map<Item, Integer> needed) {
        Map<Item, long[]> me = MbPlacer.AE2 ? AeLink.snapshot(player, tool, needed.keySet()) : null;
        List<Row> rows = new ArrayList<>();
        needed.forEach((item, n) -> {
            long[] m = me == null ? null : me.get(item);
            rows.add(new Row(item, n, inInventory(player.getInventory(), item), m == null ? -1 : m[0], m != null && m[1] == 1));
        });
        rows.sort(Comparator.comparingInt(Row::missing).reversed().thenComparing(r -> ForgeRegistries.ITEMS.getKey(r.item()).toString()));
        return rows;
    }

    /**
     * Removes the items: the ME part first (all or nothing), then the inventory part.
     * Call only after checking that every row has missing() == 0. Returns false and takes nothing if ME fell short.
     */
    public static boolean take(ServerPlayer player, ItemStack tool, List<Row> rows) {
        Map<Item, Long> fromMe = new HashMap<>();
        for (Row r : rows) if (r.needed() > r.inventory()) fromMe.put(r.item(), (long) (r.needed() - r.inventory()));
        if (!fromMe.isEmpty() && !(MbPlacer.AE2 && AeLink.take(player, tool, fromMe))) return false;
        Inventory inv = player.getInventory();
        for (Row r : rows) {
            int left = Math.min(r.needed(), r.inventory());
            for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(r.item())) {
                    int n = Math.min(left, s.getCount());
                    s.shrink(n);
                    left -= n;
                }
            }
        }
        return true;
    }
}
