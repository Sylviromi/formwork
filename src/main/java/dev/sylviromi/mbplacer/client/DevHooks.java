package dev.sylviromi.mbplacer.client;

import dev.sylviromi.mbplacer.MbPlacer;
import dev.sylviromi.mbplacer.build.Placements;
import dev.sylviromi.mbplacer.build.Placer;
import dev.sylviromi.mbplacer.build.Resolver;
import dev.sylviromi.mbplacer.build.Updater;
import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.net.ClientData;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.function.Consumer;

/**
 * Dev-only test run, enabled with -Dmbplacer.dev=true (./gradlew runClient -Pmbdev).
 * Clears the test area, screenshots the browser, editor tabs (Mekanism, void miner twice for its animated textures,
 * Masterful Machinery with the structure check and block picker), placement card and ghost, then places a tier 9 void
 * miner with speed modifiers, edits it to item modifiers (a timed job) and dismantles it by holding use, logging each
 * result. Then quits. MM needs `./gradlew copyPackMods`, the pack's MM startup scripts in run/kubejs and
 * tools/dev_mm_structures.py.
 */
@Mod.EventBusSubscriber(modid = MbPlacer.ID, value = Dist.CLIENT)
public final class DevHooks {
    private static final boolean ON = Boolean.getBoolean("mbplacer.dev");
    private static final String TURBINE = "mbplacer:gen/mekanism_turbine", VOID_MINER = "mbtool:voidminer/voidminer",
            FISSION = "mbplacer:gen/mekanism_fission_reactor", MM = "mbtool:dev_mm/water_condenser", MODIFIERS = "mbplacer:voidminer_modifiers",
            SPEED = "voidminers:ultimate_speed_modifier", ITEM = "voidminers:ultimate_item_modifier";
    private static int tick;

    /** At tick `at` of the run: an action, and/or a screenshot named shot. */
    private record Step(int at, String shot, Consumer<Minecraft> action) {
    }

    private static final List<Step> STEPS = new ArrayList<>();

    private static void step(int at, String shot, Consumer<Minecraft> action) {
        STEPS.add(new Step(at, shot, action));
    }

    static {
        int t = 60;
        step(20, null, mc -> onServer(mc, DevHooks::reset));
        step(t, null, mc -> mc.setScreen(new BrowserScreen()));
        step(t += 50, "browser", null);
        step(t += 10, null, mc -> editor(mc, TURBINE, "SETTINGS", null));
        step(t += 50, "editor_settings", null);
        step(t += 10, null, mc -> editor(mc, VOID_MINER, "VARIANTS", e -> {
            e.devCount(MODIFIERS, "voidminers:verdium_speed_modifier", 4);
            e.devCount(MODIFIERS, "voidminers:verdium_energy_modifier", 4);
        }));
        step(t += 50, "editor_variants", null);
        step(t += 55, "editor_variants_later", null); // 2.75 s later: the ultimate textures change frame every 2 s
        step(t += 5, null, mc -> editor(mc, MM, "VARIANTS", EditorScreen::devWrongPort));
        step(t += 50, "editor_mm_check", null);
        step(t += 5, null, mc -> { if (mc.screen instanceof EditorScreen e) e.devPickFirstSlot(); });
        step(t += 20, "editor_mm_picker", null);
        step(t += 10, null, mc -> editor(mc, FISSION, "MATERIALS", null));
        step(t += 50, "editor_materials", null);
        step(t += 10, null, mc -> editor(mc, FISSION, "SETTINGS", e -> e.devLayer(3)));
        step(t += 50, "editor_layers", null);
        step(t += 10, null, mc -> {
            editor(mc, TURBINE, "SETTINGS", null);
            mc.setScreen(null);
            mc.player.setXRot(55);
            mc.setScreen(new PlacementScreen());
        });
        step(t += 50, "placement", null);
        step(t += 10, null, mc -> mc.setScreen(null));
        step(t += 40, "ghost", null);
        step(t += 10, null, DevHooks::placeVoidMiner);
        step(t += 40, null, DevHooks::aimAtPlaced);
        step(t += 10, "hover", null);
        step(t += 10, null, mc -> onServer(mc, DevHooks::updateLooked));
        step(t += 15, "updating", null);
        step(t += 110, null, mc -> onServer(mc, sp -> log(sp, "after update")));
        step(t += 10, null, mc -> KeyMapping.set(mc.options.keyUse.getKey(), true));
        step(t += 30, "dismantle_hold", null);
        step(t += 90, null, mc -> {
            KeyMapping.set(mc.options.keyUse.getKey(), false);
            onServer(mc, sp -> log(sp, "after holding use"));
        });
        // ME range: a powered access point with 8 boosters (~38 blocks with AE2's default config, 16 without)
        step(t += 20, null, mc -> onServer(mc, DevHooks::buildAccessPoint));
        step(t += 60, null, mc -> onServer(mc, sp -> meAt(sp, 10)));
        step(t += 20, null, mc -> onServer(mc, sp -> meAt(sp, 30)));
        step(t += 20, null, mc -> onServer(mc, sp -> meAt(sp, 80)));
        // AEInfinityBooster: an infinity card in the access point instead of boosters (only if the mod is in the dev run)
        step(t += 20, null, mc -> onServer(mc, DevHooks::infinityCard));
        step(t += 60, null, mc -> onServer(mc, sp -> meAt(sp, 80)));
        // AE2's own craft screen from the wand, next to the access point
        step(t += 20, null, mc -> onServer(mc, sp -> {
            meAt(sp, 5);
            dev.sylviromi.mbplacer.ae2.AeLink.openCraft(sp, net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.Items.STONE, 64);
        }));
        step(t += 40, "ae2_craft", null);
        step(t += 20, null, Minecraft::stop);
    }

    private DevHooks() {
    }

    private static void editor(Minecraft mc, String key, String tab, Consumer<EditorScreen> setup) {
        Catalog.client.entries().stream().filter(en -> en.key().equals(key)).findFirst().ifPresentOrElse(en -> {
            EditorScreen e = new EditorScreen(en);
            mc.setScreen(e);
            e.showTab(tab);
            if (setup != null) setup.accept(e);
        }, () -> MbPlacer.LOG.warn("dev: entry {} not in the catalog", key));
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (!ON || e.phase != TickEvent.Phase.END || mc.player == null) return;
        tick++;
        for (Step s : STEPS)
            if (s.at() == tick) {
                if (s.action() != null) s.action().accept(mc);
                if (s.shot() != null) shot(mc, s.shot());
            }
    }

    private static void onServer(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer();
        var id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) action.accept(sp);
        });
    }

    /** Fresh start: only the tool in the inventory, no placements left from earlier runs, empty air around the player. */
    private static void reset(ServerPlayer sp) {
        ServerLevel level = sp.serverLevel();
        Placements p = Placements.of(level);
        List.copyOf(p.all()).forEach(r -> p.remove(level, r.id()));
        sp.getInventory().clearContent();
        sp.getInventory().setItem(sp.getInventory().selected, new ItemStack(MbPlacer.PLACER.get()));
        var src = sp.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        sp.server.getCommands().performPrefixedCommand(src, "fill ~-15 ~ ~-5 ~15 ~20 ~25 air");
        sp.server.getCommands().performPrefixedCommand(src, "time set day");
        sp.server.getCommands().performPrefixedCommand(src, "gamemode creative"); // edits are paid for; the test inventory is empty
    }

    private static String tier9(Catalog c) {
        return c.entries().stream().filter(en -> en.key().equals(VOID_MINER)).findFirst().map(en -> en.members().get(en.members().size() - 1)).orElseThrow();
    }

    /** The first n modifier slots of the tier 9 void miner set to this block. */
    private static Map<BlockPos, String> modifiers(String block, int n) {
        String id = tier9(Catalog.server);
        var slots = Resolver.slots(Catalog.server, id, Resolver.specs(Catalog.server, new Selection(id, new int[0], Map.of())));
        Map<BlockPos, String> out = new LinkedHashMap<>();
        slots.entrySet().stream().filter(en -> en.getValue().id().equals(MODIFIERS)).limit(n).forEach(en -> out.put(en.getKey(), block));
        return out;
    }

    /** Server: a tier 9 void miner with 4 speed modifiers 3 blocks ahead (with a record); client: looking at it. */
    private static void placeVoidMiner(Minecraft mc) {
        onServer(mc, sp -> {
            Direction f = sp.getDirection();
            Selection sel = new Selection(tier9(Catalog.server), new int[0], Map.of(), modifiers(SPEED, 4));
            var specs = Resolver.specs(Catalog.server, sel);
            BlockPos target = Resolver.centerFor(Resolver.Footprint.of(specs.keySet()), sp.blockPosition().relative(f, 3), f, f);
            var blocks = Resolver.world(specs, target, f);
            Placer.put(sp.serverLevel(), blocks, sp);
            Placements.of(sp.serverLevel()).add(sp.serverLevel(), Placements.record(blocks, sel.save(), target, f, sp.getUUID()));
            log(sp, "placed at " + target + " facing " + f);
        });
        mc.player.setXRot(15);
    }

    /** Client: looks at the placed block nearest to the eyes (only real blocks of a placement count as pointing at it). */
    private static void aimAtPlaced(Minecraft mc) {
        var eye = mc.player.getEyePosition();
        ClientData.placements.stream().flatMap(r -> Placements.positions(r, Catalog.client).stream())
                .min(Comparator.comparingDouble(p -> p.getCenter().distanceToSqr(eye)))
                .ifPresent(p -> mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, p.getCenter()));
    }

    /** Edits the placement in view: the 4 speed modifiers become item modifiers (runs as a timed job). */
    private static void updateLooked(ServerPlayer sp) {
        Placements.Rec rec = PlacerItem.placementLookedAt(sp);
        if (rec == null) {
            MbPlacer.LOG.warn("dev: update: no placement in view");
            return;
        }
        Selection sel = Selection.load(rec.selection());
        Updater.apply(sp, sp.getMainHandItem(), rec.id(), new Selection(sel.entry(), sel.params(), Map.of(), modifiers(ITEM, 4)));
        log(sp, "update started");
    }

    private static BlockPos wap;

    private static void buildAccessPoint(ServerPlayer sp) {
        wap = sp.blockPosition().offset(0, 0, -3);
        var src = sp.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        var cmd = sp.server.getCommands();
        cmd.performPrefixedCommand(src, "setblock " + wap.getX() + " " + wap.getY() + " " + wap.getZ() + " ae2:creative_energy_cell");
        cmd.performPrefixedCommand(src, "setblock " + wap.getX() + " " + (wap.getY() + 1) + " " + wap.getZ()
                + " ae2:wireless_access_point[facing=up]{inv:{item0:{id:\"ae2:wireless_booster\",Count:8b}}}");
        GlobalPos at = GlobalPos.of(sp.level().dimension(), wap.above());
        GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, at).result().ifPresent(tag -> sp.getMainHandItem().getOrCreateTag().put(PlacerItem.AE_LINK, tag));
        MbPlacer.LOG.info("dev: access point at {} with 8 boosters, tool linked", wap.above());
    }

    private static void infinityCard(ServerPlayer sp) {
        var src = sp.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        sp.server.getCommands().performPrefixedCommand(src, "setblock " + wap.getX() + " " + (wap.getY() + 1) + " " + wap.getZ()
                + " ae2:wireless_access_point[facing=up]{inv:{item0:{id:\"aeinfinitybooster:infinity_card\",Count:1b}}} replace");
        MbPlacer.LOG.info("dev: access point now holds an infinity card");
    }

    private static void meAt(ServerPlayer sp, int distance) {
        sp.teleportTo(wap.getX() + distance + 0.5, wap.getY(), wap.getZ() + 0.5);
        var be = sp.level().getBlockEntity(wap.above());
        String info = be instanceof appeng.api.implementations.blockentities.IWirelessAccessPoint w
                ? "grid " + (w.getGrid() != null) + ", active " + w.isActive() + ", range " + w.getRange()
                + (w.getGrid() == null ? "" : ", machines " + w.getGrid().getMachineClasses()) : "not an access point: " + be;
        MbPlacer.LOG.info("dev: ME at {} blocks: {} ({})", distance, dev.sylviromi.mbplacer.build.Materials.meStatus(sp, sp.getMainHandItem()), info);
    }

    private static BlockPos lastMin, lastMax;

    /** Logs placements left and the modifiers inside the newest placement's bounds (or the last bounds seen). */
    private static void log(ServerPlayer sp, String what) {
        ServerLevel level = sp.serverLevel();
        var all = Placements.of(level).all();
        all.stream().reduce((a, b) -> b).ifPresent(r -> {
            lastMin = new BlockPos(r.box().minX(), r.box().minY(), r.box().minZ());
            lastMax = new BlockPos(r.box().maxX(), r.box().maxY(), r.box().maxZ());
        });
        long speed = 0, item = 0, blocks = 0;
        if (lastMin != null)
            for (BlockPos p : BlockPos.betweenClosed(lastMin, lastMax)) {
                var s = level.getBlockState(p);
                if (s.isAir()) continue;
                blocks++;
                String id = String.valueOf(ForgeRegistries.BLOCKS.getKey(s.getBlock()));
                if (id.equals(SPEED)) speed++;
                if (id.equals(ITEM)) item++;
            }
        MbPlacer.LOG.info("dev: {}: {} placements {}, {} blocks in bounds, {} speed / {} item modifiers", what, all.size(),
                all.stream().map(r -> Selection.load(r.selection()).entry() + "@" + r.target()).toList(), blocks, speed, item);
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "mbplacer_" + name + ".png", mc.getMainRenderTarget(), msg -> MbPlacer.LOG.info("dev screenshot: {}", msg.getString()));
    }
}
