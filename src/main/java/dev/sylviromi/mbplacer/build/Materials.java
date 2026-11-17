package dev.sylviromi.mbplacer.build;

import dev.sylviromi.mbplacer.MbPlacer;
import dev.sylviromi.mbplacer.ae2.AeLink;
import dev.sylviromi.mbplacer.structure.BlockSpec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
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
    /** crafting: percent done of an AE2 job making this item, or -1; eta: its estimated seconds left, or -1 when not known yet. */
    public record Row(Item item, int needed, int inventory, long me, boolean craftable, int crafting, int eta) {
        public int missing() {
            return (int) Math.max(0, needed - inventory - Math.max(0, me));
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeId(net.minecraft.core.registries.BuiltInRegistries.ITEM, item);
            buf.writeVarInt(needed).writeVarInt(inventory).writeVarLong(me + 1).writeBoolean(craftable);
            buf.writeVarInt(crafting + 1).writeVarInt(eta + 1);
        }

        public static Row read(FriendlyByteBuf buf) {
            return new Row(buf.readById(net.minecraft.core.registries.BuiltInRegistries.ITEM), buf.readVarInt(), buf.readVarInt(), buf.readVarLong() - 1, buf.readBoolean(),
                    buf.readVarInt() - 1, buf.readVarInt() - 1);
        }
    }

    /** ME status shown in the GUI. */
    public enum MeStatus {NO_AE2, NOT_LINKED, OUT_OF_RANGE, CONNECTED}

    private static final ResourceLocation TURBINE_ROTOR = new ResourceLocation("mekanismgenerators", "turbine_rotor"),
            TURBINE_BLADE = new ResourceLocation("mekanismgenerators", "turbine_blade");

    // shortcut: blocks without an item (fluids, technical blocks) are placed for free; charge buckets if a pack needs it.
    public static Map<Item, Integer> needed(Collection<Resolver.Placed> blocks) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Resolver.Placed b : blocks) {
            Item item = b.state().getBlock().asItem();
            if (item != Items.AIR) out.merge(item, 1, Integer::sum);
            // items stored in block entity nbt are placed too, so they cost. shortcut: only turbine blades so far
            if (b.nbt() != null && b.nbt().getInt("blades") > 0 && TURBINE_ROTOR.equals(ForgeRegistries.BLOCKS.getKey(b.state().getBlock()))) {
                Item blade = ForgeRegistries.ITEMS.getValue(TURBINE_BLADE);
                if (blade != null && blade != Items.AIR) out.merge(blade, b.nbt().getInt("blades"), Integer::sum);
            }
        }
        return out;
    }

    public static Map<Item, Integer> neededForSpecs(Collection<BlockSpec> specs) {
        List<Resolver.Placed> blocks = new ArrayList<>();
        for (BlockSpec s : specs) {
            BlockState state = Resolver.state(s);
            if (state != null) blocks.add(new Resolver.Placed(state, s.nbt()));
        }
        return needed(blocks);
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
            rows.add(new Row(item, n, inInventory(player.getInventory(), item), m == null ? -1 : m[0], m != null && m[1] == 1,
                    m == null ? -1 : (int) m[2], m == null ? -1 : (int) m[3]));
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
