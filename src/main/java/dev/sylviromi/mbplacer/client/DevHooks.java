package dev.sylviromi.mbplacer.client;

import dev.sylviromi.mbplacer.MbPlacer;
import dev.sylviromi.mbplacer.build.Dismantler;
import dev.sylviromi.mbplacer.build.Placements;
import dev.sylviromi.mbplacer.build.Placer;
import dev.sylviromi.mbplacer.build.Resolver;
import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dev-only test run, enabled with -Dmbplacer.dev=true (./gradlew runClient -Pmbdev).
 * Clears the test area, shows the browser, editor tabs (Mekanism, void miner, Masterful Machinery), placement card and ghost,
 * saves screenshots to run/screenshots, then places an MM water condenser with a port variant, dismantles it and logs the result.
 * MM needs `./gradlew copyPackMods`, the pack's MM startup scripts in run/kubejs and tools/dev_mm_structures.py.
 */
@Mod.EventBusSubscriber(modid = MbPlacer.ID, value = Dist.CLIENT)
public final class DevHooks {
    private static final boolean ON = Boolean.getBoolean("mbplacer.dev");
    private static final String TURBINE = "mbplacer:gen/mekanism_turbine", VOID_MINER = "mbtool:voidminer/voidminer",
            FISSION = "mbplacer:gen/mekanism_fission_reactor", MM = "mbtool:dev_mm/water_condenser",
            MM_ENERGY = "mbplacer:mm_energy_port_input", TINY_PORT = "mm:tiny_energy_port_input";
    private static int tick;

    /** action at tick 0 of the step, screenshot at tick `at`. */
    private record Step(String shot, int at, Consumer<Minecraft> action) {
        Step(String shot, Consumer<Minecraft> action) {
            this(shot, 50, action);
        }
    }

    private static final List<Step> STEPS = List.of(
            new Step("browser", mc -> mc.setScreen(new BrowserScreen())),
            new Step("editor_settings", mc -> editor(mc, TURBINE, "SETTINGS", null)),
            new Step("editor_variants", mc -> editor(mc, VOID_MINER, "VARIANTS", e -> {
                e.devCount("mbplacer:voidminer_modifiers", "voidminers:verdium_speed_modifier", 4);
                e.devCount("mbplacer:voidminer_modifiers", "voidminers:verdium_energy_modifier", 4);
            })),
            new Step("editor_mm", mc -> editor(mc, MM, "VARIANTS", e -> e.devCount(MM_ENERGY, TINY_PORT, 1))),
            new Step("editor_materials", mc -> editor(mc, FISSION, "MATERIALS", null)),
            new Step("editor_layers", mc -> editor(mc, FISSION, "SETTINGS", e -> e.devLayer(3))),
            new Step("placement", mc -> {
                editor(mc, TURBINE, "SETTINGS", null);
                mc.setScreen(null);
                mc.player.setXRot(55);
                mc.setScreen(new PlacementScreen());
            }),
            new Step("ghost", mc -> mc.setScreen(null)),
            new Step("hover_dismantle", DevHooks::placeAndAim),
            new Step("hover_edit", mc -> {
                setDismantle(false);
                onServer(mc, DevHooks::updateLooked);
            }),
            new Step("dismantle_hold", 12, mc -> {
                setDismantle(true);
                KeyMapping.set(mc.options.keyUse.getKey(), true);
            }));

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
        if (tick == 20) onServer(mc, DevHooks::reset);
        int step = tick / 60 - 1, phase = tick % 60;
        if (step >= 0 && step < STEPS.size()) {
            if (phase == 0) STEPS.get(step).action().accept(mc);
            if (phase == STEPS.get(step).at()) shot(mc, STEPS.get(step).shot());
        }
        if (step == STEPS.size() && phase == 0) {
            KeyMapping.set(mc.options.keyUse.getKey(), false);
            onServer(mc, sp -> MbPlacer.LOG.info("dev: after holding use: {} placements left, {} tiny ports in the old area",
                    Placements.of(sp.serverLevel()).all().size(), count(sp.serverLevel(), lastArea, TINY_PORT)));
        }
        if (step == STEPS.size() && phase == 20) mc.stop();
    }

    private static void onServer(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer();
        var id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) action.accept(sp);
        });
    }

    /** Fresh start: the tool in hand, no placements left from earlier runs, empty air around the player. */
    private static void reset(ServerPlayer sp) {
        ServerLevel level = sp.serverLevel();
        Placements p = Placements.of(level);
        List.copyOf(p.all()).forEach(r -> p.remove(level, r.id()));
        sp.getInventory().setItem(sp.getInventory().selected, new ItemStack(MbPlacer.PLACER.get()));
        var src = sp.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        sp.server.getCommands().performPrefixedCommand(src, "fill ~-15 ~ ~-15 ~15 ~20 ~15 air");
    }

    /** Server: places an MM water condenser with one tiny energy port 3 blocks ahead; client: dismantle mode, looking at it. */
    private static void placeAndAim(Minecraft mc) {
        onServer(mc, sp -> {
            Direction f = sp.getDirection();
            BlockPos target = sp.blockPosition().relative(f, 3);
            String id = Catalog.server.entries().stream().filter(en -> en.key().equals(MM)).findFirst().map(en -> en.members().get(0)).orElse(TURBINE);
            Selection sel = new Selection(id, new int[0], id.equals(TURBINE) ? Map.of() : Map.of(MM_ENERGY, Map.of(TINY_PORT, 1)));
            var blocks = Resolver.world(Resolver.specs(Catalog.server, sel), target, f);
            Placer.put(sp.serverLevel(), blocks, sp);
            Placements.of(sp.serverLevel()).add(sp.serverLevel(), Placements.record(blocks, sel.save(), target, f, sp.getUUID()));
            MbPlacer.LOG.info("dev: placed {} ({} blocks, {} tiny ports) at {} facing {}", id, blocks.size(), count(sp.serverLevel(), blocks.keySet(), TINY_PORT), target, f);
        });
        setDismantle(true);
        mc.player.setXRot(20);
    }

    private static long count(ServerLevel level, java.util.Collection<BlockPos> at, String block) {
        return at.stream().filter(p -> block.equals(String.valueOf(ForgeRegistries.BLOCKS.getKey(level.getBlockState(p).getBlock())))).count();
    }

    private static java.util.Set<BlockPos> lastArea = java.util.Set.of();

    private static void setDismantle(boolean on) {
        ItemStack tool = WorldGhost.heldTool();
        CompoundTag t = PlacerItem.placementTag(tool);
        t.putBoolean(PlacerItem.DISMANTLE, on);
        ClientHooks.setPlacement(tool, t);
    }

    /** Edits the placement in view to two tiny energy ports and logs the count before and after. */
    private static void updateLooked(ServerPlayer sp) {
        Placements.Rec rec = PlacerItem.placementLookedAt(sp);
        if (rec == null) {
            MbPlacer.LOG.warn("dev: update: no placement in view");
            return;
        }
        var before = Dismantler.blocks(Catalog.server, rec).keySet();
        long tiny = count(sp.serverLevel(), before, TINY_PORT);
        Selection sel = Selection.load(rec.selection());
        Selection two = new Selection(sel.entry(), sel.params(), Map.of(MM_ENERGY, Map.of(TINY_PORT, 2)));
        dev.sylviromi.mbplacer.build.Updater.apply(sp, sp.getMainHandItem(), rec.id(), two);
        Placements.Rec now = PlacerItem.placementLookedAt(sp);
        lastArea = java.util.Set.copyOf(before);
        MbPlacer.LOG.info("dev: update: tiny ports {} -> {}, record updated: {}", tiny, count(sp.serverLevel(), before, TINY_PORT),
                now != null && Selection.load(now.selection()).counts().equals(two.counts()));
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "mbplacer_" + name + ".png", mc.getMainRenderTarget(), msg -> MbPlacer.LOG.info("dev screenshot: {}", msg.getString()));
    }
}
