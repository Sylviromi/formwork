package dev.sylviromi.mbplacer.net;

import dev.sylviromi.mbplacer.MbPlacer;
import dev.sylviromi.mbplacer.ae2.AeLink;
import dev.sylviromi.mbplacer.build.Materials;
import dev.sylviromi.mbplacer.build.Resolver;
import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.structure.BlockSpec;
import dev.sylviromi.mbplacer.structure.Blueprint;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import dev.sylviromi.mbplacer.structure.VariantSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.*;
import java.util.function.Supplier;

/** All packets. Server to client: catalog sync and material reports. Client to server: selection, report query, craft request. */
public final class Net {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MbPlacer.ID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    private Net() {
    }

    // ---- packets ----
    public record CatalogReset(List<VariantSet> variants) {
        void write(FriendlyByteBuf b) {
            b.writeCollection(variants, (buf, v) -> {
                buf.writeUtf(v.id());
                buf.writeCollection(v.blocks(), FriendlyByteBuf::writeUtf);
                buf.writeUtf(v.match());
            });
        }

        static CatalogReset read(FriendlyByteBuf b) {
            return new CatalogReset(b.readList(buf -> new VariantSet(buf.readUtf(), buf.readList(FriendlyByteBuf::readUtf), buf.readUtf())));
        }
    }

    /** One blueprint as a palette plus packed positions; raw structure NBT can exceed the packet NBT limit. */
    public record BlueprintSync(Blueprint bp) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(bp.id());
            List<BlockSpec> palette = new ArrayList<>();
            Map<BlockSpec, Integer> index = new HashMap<>();
            bp.blocks().values().forEach(spec -> index.computeIfAbsent(spec, k -> { palette.add(k); return palette.size() - 1; }));
            b.writeCollection(palette, (buf, spec) -> {
                buf.writeUtf(spec.id());
                buf.writeMap(spec.props(), FriendlyByteBuf::writeUtf, FriendlyByteBuf::writeUtf);
                buf.writeNbt(spec.nbt());
            });
            b.writeVarInt(bp.blocks().size());
            bp.blocks().forEach((pos, spec) -> {
                b.writeLong(pos.asLong());
                b.writeVarInt(index.get(spec));
            });
        }

        static BlueprintSync read(FriendlyByteBuf b) {
            String id = b.readUtf();
            List<BlockSpec> palette = b.readList(buf -> new BlockSpec(buf.readUtf(),
                    Map.copyOf(buf.readMap(FriendlyByteBuf::readUtf, FriendlyByteBuf::readUtf)), buf.readNbt()));
            int n = b.readVarInt();
            Map<BlockPos, BlockSpec> blocks = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) blocks.put(BlockPos.of(b.readLong()), palette.get(b.readVarInt()));
            return new BlueprintSync(new Blueprint(id, blocks));
        }
    }

    public record SetSelection(CompoundTag selection) {
        void write(FriendlyByteBuf b) {
            b.writeNbt(selection);
        }

        static SetSelection read(FriendlyByteBuf b) {
            return new SetSelection(b.readNbt());
        }
    }

    public record RequestCrafts() {
    }

    public record Report(Materials.MeStatus me, String entry, int blocks, List<Materials.Row> rows) {
        void write(FriendlyByteBuf b) {
            b.writeEnum(me);
            b.writeUtf(entry);
            b.writeVarInt(blocks);
            b.writeCollection(rows, (buf, r) -> r.write(buf));
        }

        static Report read(FriendlyByteBuf b) {
            return new Report(b.readEnum(Materials.MeStatus.class), b.readUtf(), b.readVarInt(), b.readList(Materials.Row::read));
        }
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(CatalogReset.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CatalogReset::write).decoder(CatalogReset::read).consumerMainThread(Net::onReset).add();
        CHANNEL.messageBuilder(BlueprintSync.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BlueprintSync::write).decoder(BlueprintSync::read).consumerMainThread(Net::onBlueprint).add();
        CHANNEL.messageBuilder(Report.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Report::write).decoder(Report::read).consumerMainThread(Net::onReport).add();
        CHANNEL.messageBuilder(SetSelection.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetSelection::write).decoder(SetSelection::read).consumerMainThread(Net::onSetSelection).add();
        CHANNEL.messageBuilder(RequestCrafts.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((m, b) -> {}).decoder(b -> new RequestCrafts()).consumerMainThread(Net::onRequestCrafts).add();
    }

    /** Sends the whole catalog on join and after /reload. */
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        List<ServerPlayer> players = event.getPlayer() != null ? List.of(event.getPlayer()) : event.getPlayerList().getPlayers();
        Catalog c = Catalog.server;
        for (ServerPlayer p : players) {
            var target = PacketDistributor.PLAYER.with(() -> p);
            CHANNEL.send(target, new CatalogReset(List.copyOf(c.variants.values())));
            c.blueprints.values().forEach(bp -> CHANNEL.send(target, new BlueprintSync(bp)));
        }
    }

    // ---- handlers ----
    private static void onReset(CatalogReset m, Supplier<NetworkEvent.Context> ctx) {
        Catalog c = new Catalog();
        m.variants().forEach(c::addVariants);
        Catalog.client = c;
    }

    private static void onBlueprint(BlueprintSync m, Supplier<NetworkEvent.Context> ctx) {
        Catalog.client.addBlueprint(m.bp());
    }

    private static void onReport(Report m, Supplier<NetworkEvent.Context> ctx) {
        ClientData.rows = m.rows();
        ClientData.me = m.me();
        ClientData.reportFor = m.entry();
        ClientData.blocks = m.blocks();
    }

    private static ItemStack heldTool(ServerPlayer p) {
        for (InteractionHand hand : InteractionHand.values())
            if (p.getItemInHand(hand).getItem() instanceof PlacerItem) return p.getItemInHand(hand);
        return ItemStack.EMPTY;
    }

    private static void onSetSelection(SetSelection m, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ItemStack tool = p == null ? ItemStack.EMPTY : heldTool(p);
        if (tool.isEmpty() || m.selection() == null) return;
        // validated again at placement; here we only refuse entries the server does not know
        Selection sel = Selection.load(m.selection());
        if (Resolver.specs(Catalog.server, sel) == null) return;
        PlacerItem.setSelection(tool, sel);
        sendReport(p, tool);
    }

    /** Material report for the tool's current selection. */
    public static void sendReport(ServerPlayer p, ItemStack tool) {
        Selection sel = PlacerItem.selection(tool);
        Map<BlockPos, BlockSpec> specs = sel.isEmpty() ? null : Resolver.specs(Catalog.server, sel);
        List<Materials.Row> rows = specs == null ? List.of() : Materials.report(p, tool, Materials.neededForSpecs(specs.values()));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
                new Report(Materials.meStatus(p, tool), sel.entry(), specs == null ? 0 : specs.size(), rows));
    }

    private static void onRequestCrafts(RequestCrafts m, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ItemStack tool = p == null ? ItemStack.EMPTY : heldTool(p);
        if (tool.isEmpty() || !MbPlacer.AE2) return;
        Selection sel = PlacerItem.selection(tool);
        Map<BlockPos, BlockSpec> specs = Resolver.specs(Catalog.server, sel);
        if (specs == null) return;
        Map<Item, Long> missing = new LinkedHashMap<>();
        for (Materials.Row r : Materials.report(p, tool, Materials.neededForSpecs(specs.values())))
            if (r.missing() > 0 && r.craftable()) missing.put(r.item(), (long) r.missing());
        AeLink.requestCrafts(p, tool, missing);
    }
}
