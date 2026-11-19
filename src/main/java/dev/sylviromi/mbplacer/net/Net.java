package dev.sylviromi.mbplacer.net;

import dev.sylviromi.mbplacer.MbPlacer;
import dev.sylviromi.mbplacer.ae2.AeLink;
import dev.sylviromi.mbplacer.build.Materials;
import dev.sylviromi.mbplacer.build.Placements;
import dev.sylviromi.mbplacer.build.Updater;
import dev.sylviromi.mbplacer.build.Resolver;
import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.structure.BlockSpec;
import dev.sylviromi.mbplacer.structure.Blueprint;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import dev.sylviromi.mbplacer.structure.VariantSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.*;
import java.util.function.Supplier;

/** All packets. Server to client: catalog sync and material reports. Client to server: selection, report query, craft request. */
public final class Net {
    private static final String VERSION = "11";
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
                buf.writeVarInt(v.columns());
                buf.writeUtf(v.family());
                buf.writeBoolean(v.walls());
            });
        }

        static CatalogReset read(FriendlyByteBuf b) {
            return new CatalogReset(b.readList(buf -> new VariantSet(buf.readUtf(), buf.readList(FriendlyByteBuf::readUtf), buf.readUtf(), buf.readVarInt(), buf.readUtf(), buf.readBoolean())));
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

    /** Anchor, offset, rotation and mode of the held tool (see PlacerItem.placementTag). */
    public record SetPlacement(CompoundTag placement) {
        void write(FriendlyByteBuf b) {
            b.writeNbt(placement);
        }

        static SetPlacement read(FriendlyByteBuf b) {
            return new SetPlacement(b.readNbt());
        }
    }

    /** All placements in the player's dimension (see build/Placements). */
    public record PlacementsSync(List<Placements.Rec> recs) {
        void write(FriendlyByteBuf b) {
            b.writeCollection(recs, (buf, r) -> buf.writeNbt(r.save()));
        }

        static PlacementsSync read(FriendlyByteBuf b) {
            return new PlacementsSync(b.readList(buf -> Placements.Rec.load(buf.readNbt())));
        }
    }

    /** Editing a placed multiblock: preview asks for the change's material report, apply performs it. */
    public record UpdatePlaced(UUID placement, CompoundTag selection, boolean apply) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(placement);
            b.writeNbt(selection);
            b.writeBoolean(apply);
        }

        static UpdatePlaced read(FriendlyByteBuf b) {
            return new UpdatePlaced(b.readUUID(), b.readNbt(), b.readBoolean());
        }
    }

    /** An update of a placement waits this many ticks before it happens; 0 = it happened or was cancelled. */
    public record Progress(UUID placement, int ticks) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(placement);
            b.writeVarInt(ticks);
        }

        static Progress read(FriendlyByteBuf b) {
            return new Progress(b.readUUID(), b.readVarInt());
        }
    }

    /** The facing the client's ghost uses (with a dead zone at the diagonals) and whether Ctrl skips blocked spots. */
    public record Aim(Direction facing, boolean skipBlocked) {
        void write(FriendlyByteBuf b) {
            b.writeEnum(facing);
            b.writeBoolean(skipBlocked);
        }

        static Aim read(FriendlyByteBuf b) {
            return new Aim(b.readEnum(Direction.class), b.readBoolean());
        }
    }

    private static final Map<UUID, Aim> AIMS = new java.util.concurrent.ConcurrentHashMap<>();

    /** What this player's client last reported, or their plain facing. */
    public static Aim aim(ServerPlayer p) {
        Aim a = AIMS.get(p.getUUID());
        return a != null && a.facing().getAxis().isHorizontal() ? a : new Aim(p.getDirection(), false);
    }

    /** Open AE2's craft screen for this item (a missing material) on the tool's grid. */
    public record OpenCraft(Item item, int amount) {
        void write(FriendlyByteBuf b) {
            b.writeId(net.minecraft.core.registries.BuiltInRegistries.ITEM, item);
            b.writeVarInt(amount);
        }

        static OpenCraft read(FriendlyByteBuf b) {
            return new OpenCraft(b.readById(net.minecraft.core.registries.BuiltInRegistries.ITEM), b.readVarInt());
        }
    }

    /** Open AE2's craft confirm screens for all these missing materials, one after another. */
    public record CraftAll(Map<Item, Integer> amounts) {
        void write(FriendlyByteBuf b) {
            b.writeMap(amounts, (o, i) -> o.writeId(net.minecraft.core.registries.BuiltInRegistries.ITEM, i), FriendlyByteBuf::writeVarInt);
        }

        static CraftAll read(FriendlyByteBuf b) {
            return new CraftAll(b.readMap(i -> i.readById(net.minecraft.core.registries.BuiltInRegistries.ITEM), FriendlyByteBuf::readVarInt));
        }
    }

    /** AE2's craft screen is done: reopen the editor it was opened from. */
    public record ReturnToEditor() {
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
        CHANNEL.messageBuilder(OpenCraft.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(OpenCraft::write).decoder(OpenCraft::read).consumerMainThread(Net::onOpenCraft).add();
        CHANNEL.messageBuilder(ReturnToEditor.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((m, b) -> {}).decoder(b -> new ReturnToEditor())
                .consumerMainThread((m, ctx) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> dev.sylviromi.mbplacer.client.ClientHooks::resumeEditor)).add();
        CHANNEL.messageBuilder(CraftAll.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CraftAll::write).decoder(CraftAll::read).consumerMainThread(Net::onCraftAll).add();
        CHANNEL.messageBuilder(PlacementsSync.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PlacementsSync::write).decoder(PlacementsSync::read).consumerMainThread((m, ctx) -> { ClientData.placements = m.recs(); }).add();
        CHANNEL.messageBuilder(Progress.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Progress::write).decoder(Progress::read).consumerMainThread((m, ctx) -> {
                    ClientData.progressFor = m.ticks() == 0 ? null : m.placement();
                    ClientData.progressStart = System.currentTimeMillis();
                    ClientData.progressMillis = m.ticks() * 50L;
                }).add();
        CHANNEL.messageBuilder(Aim.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Aim::write).decoder(Aim::read).consumerMainThread((m, ctx) -> {
                    if (ctx.get().getSender() != null) AIMS.put(ctx.get().getSender().getUUID(), m);
                }).add();
        CHANNEL.messageBuilder(UpdatePlaced.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UpdatePlaced::write).decoder(UpdatePlaced::read).consumerMainThread(Net::onUpdatePlaced).add();
        CHANNEL.messageBuilder(SetPlacement.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetPlacement::write).decoder(SetPlacement::read).consumerMainThread(Net::onSetPlacement).add();
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

    /** On login and dimension change: the placements of the player's new dimension. */
    public static void syncPlacements(ServerPlayer p) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new PlacementsSync(List.copyOf(Placements.of(p.serverLevel()).all())));
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

    private static void onSetPlacement(SetPlacement m, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ItemStack tool = p == null ? ItemStack.EMPTY : heldTool(p);
        if (!tool.isEmpty() && m.placement() != null) PlacerItem.applyPlacement(tool, m.placement(), p.blockPosition());
    }

    private static void onUpdatePlaced(UpdatePlaced m, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ItemStack tool = p == null ? ItemStack.EMPTY : heldTool(p);
        if (tool.isEmpty() || m.selection() == null) return;
        Selection sel = Selection.load(m.selection());
        if (m.apply()) {
            Updater.apply(p, tool, m.placement(), sel);
            return;
        }
        Placements.of(p.serverLevel()).all().stream().filter(r -> r.id().equals(m.placement())).findFirst().ifPresent(rec -> {
            Updater.Plan plan = Updater.plan(p.serverLevel(), rec, sel);
            if (plan != null) CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
                    new Report(Materials.meStatus(p, tool), sel.entry(), plan.all().size(), Updater.report(p, tool, plan)));
        });
    }

    /** Material report for the tool's current selection. */
    public static void sendReport(ServerPlayer p, ItemStack tool) {
        Selection sel = PlacerItem.selection(tool);
        Map<BlockPos, BlockSpec> specs = sel.isEmpty() ? null : Resolver.specs(Catalog.server, sel);
        List<Materials.Row> rows = specs == null ? List.of() : Materials.report(p, tool, Materials.neededForSpecs(specs.values()));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
                new Report(Materials.meStatus(p, tool), sel.entry(), specs == null ? 0 : specs.size(), rows));
    }

    private static void onOpenCraft(OpenCraft m, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        if (p == null || !MbPlacer.AE2 || m.item() == null) return;
        for (InteractionHand hand : InteractionHand.values())
            if (p.getItemInHand(hand).getItem() instanceof PlacerItem) {
                AeLink.openCraft(p, hand, m.item(), Math.min(m.amount(), 1 << 20));
                return;
            }
    }

    private static void onCraftAll(CraftAll m, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        if (p == null || !MbPlacer.AE2) return;
        Map<Item, Integer> amounts = new LinkedHashMap<>();
        m.amounts().forEach((item, n) -> { if (item != null && n > 0) amounts.put(item, Math.min(n, 1 << 20)); });
        for (InteractionHand hand : InteractionHand.values())
            if (p.getItemInHand(hand).getItem() instanceof PlacerItem) {
                AeLink.openCraftAll(p, hand, amounts);
                return;
            }
    }
}
