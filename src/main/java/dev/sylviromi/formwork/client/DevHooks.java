package dev.sylviromi.formwork.client;

import dev.sylviromi.formwork.Formwork;
import dev.sylviromi.formwork.build.Dismantler;
import dev.sylviromi.formwork.build.Placements;
import dev.sylviromi.formwork.build.Placer;
import dev.sylviromi.formwork.build.Resolver;
import dev.sylviromi.formwork.build.Updater;
import dev.sylviromi.formwork.item.PlacerItem;
import dev.sylviromi.formwork.net.ClientData;
import dev.sylviromi.formwork.structure.Catalog;
import dev.sylviromi.formwork.structure.Selection;
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
 * Dev-only screenshot run: ./gradlew runClient -Pmbdev runs every group, -Pmbdev=gui,mirror only the named ones (comma
 * separated, see GROUPS below), so a GUI change needs no structures built. Each run resets the test area first, then quits.
 * Screenshots go to run/screenshots, results to the log ("dev: ..."). The mm group needs `./gradlew copyPackMods`, the pack's
 * MM startup scripts in run/kubejs and tools/dev_mm_structures.py.
 */
@Mod.EventBusSubscriber(modid = Formwork.ID, value = Dist.CLIENT)
public final class DevHooks {
    private static final String PROPERTY = System.getProperty("formwork.dev", "");
    private static final boolean ON = !PROPERTY.isEmpty() && !PROPERTY.equals("false");
    private static final String TURBINE = "formwork:gen/mekanism_turbine", VOID_MINER = "pi:voidminer/voidminer",
            FISSION = "formwork:gen/mekanism_fission_reactor", MM = "pi:dev_mm/water_condenser", MODIFIERS = "formwork:voidminer_modifiers",
            SPEED = "voidminers:ultimate_speed_modifier", ITEM = "voidminers:ultimate_item_modifier";
    /** -Pmbslow=N runs client and server N times slower so every frame of a recorded clip can be captured (see record). */
    private static final int SLOW = Math.max(1, Integer.getInteger("formwork.slow", 1));
    private static int tick;
    private static String recording;
    private static int recFrames;
    private static long emptySince = -1;
    /** Tick of the last step added: each group continues from there. */
    private static int t;

    /** At tick `at` of the run: an action, and/or a screenshot named shot. */
    private record Step(int at, String shot, Consumer<Minecraft> action) {
    }

    private static final List<Step> STEPS = new ArrayList<>();

    private static void step(int at, String shot, Consumer<Minecraft> action) {
        STEPS.add(new Step(at, shot, action));
    }

    /**
     * browser, editor (Mekanism tabs, void miner variants), mm (Masterful Machinery check and picker), placement (the card and
     * the ghost), mirror (the card with mirror on), place, update (edit a placed miner, timed and cancelled), dismantle (hold
     * use), animation (place and dismantle sweeps mid-way), me (access point range), craft (AE2 craft screens, craft all), jei,
     * nc (NuclearCraft structures placed, controllers logged after validating; needs copyPackMods).
     */
    private static final Map<String, Runnable> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("browser", () -> {
            step(t += 60, null, mc -> mc.setScreen(new BrowserScreen()));
            step(t += 50, "browser", null);
        });
        GROUPS.put("editor", () -> {
            step(t += 10, null, mc -> editor(mc, TURBINE, "SETTINGS", null));
            step(t += 50, "editor_settings", null);
            step(t += 10, null, mc -> editor(mc, VOID_MINER, "VARIANTS", e -> {
                e.devCount(MODIFIERS, "voidminers:verdium_speed_modifier", 4);
                e.devCount(MODIFIERS, "voidminers:verdium_energy_modifier", 4);
            }));
            step(t += 50, "editor_variants", null);
            step(t += 55, "editor_variants_later", null); // 2.75 s later: the ultimate textures change frame every 2 s
            step(t += 10, null, mc -> editor(mc, FISSION, "MATERIALS", null));
            step(t += 50, "editor_materials", null);
            step(t += 10, null, mc -> editor(mc, FISSION, "SETTINGS", e -> e.devLayer(3)));
            step(t += 50, "editor_layers", null);
        });
        GROUPS.put("mm", () -> {
            step(t += 10, null, mc -> editor(mc, MM, "VARIANTS", EditorScreen::devWrongPort));
            step(t += 50, "editor_mm_check", null);
            step(t += 5, null, mc -> { if (mc.screen instanceof EditorScreen e) e.devPickFirstSlot(); });
            step(t += 20, "editor_mm_picker", null);
        });
        GROUPS.put("placement", () -> {
            step(t += 10, null, mc -> {
                editor(mc, TURBINE, "SETTINGS", null);
                mc.setScreen(null);
                mc.player.setXRot(55);
                mc.setScreen(new PlacementScreen());
            });
            step(t += 50, "placement", null);
            step(t += 10, null, mc -> mc.setScreen(null));
            step(t += 40, "ghost", null);
        });
        GROUPS.put("mirror", () -> {
            step(t += 10, null, mc -> {
                editor(mc, TURBINE, "SETTINGS", null);
                mc.setScreen(null);
                mc.player.setXRot(12);
                onServer(mc, sp -> {
                    Direction b = sp.getDirection().getOpposite();
                    sp.teleportTo(sp.getX() + b.getStepX() * 16, sp.getY(), sp.getZ() + b.getStepZ() * 16);
                });
                setMirror(false);
                mc.setScreen(new PlacementScreen());
            });
            step(t += 50, "mirror_off", null);
            step(t += 5, null, mc -> setMirror(true));
            step(t += 15, "mirror_on", null);
            step(t += 5, null, mc -> mc.setScreen(null));
        });
        GROUPS.put("nc", () -> {
            for (int i = 0; i < 4; i++) {
                int n = i;
                step(t += 20, null, mc -> onServer(mc, sp -> placeNuclearCraft(sp, n)));
            }
            step(t += 60, null, mc -> onServer(mc, DevHooks::reportNuclearCraft));
            step(t += 300, null, mc -> onServer(mc, sp -> {
                kickNuclearCraft(sp);
                Formwork.LOG.info("dev: nc: kicked");
            }));
            step(t += 200, null, mc -> onServer(mc, DevHooks::reportNuclearCraft));
        });
        GROUPS.put("place", () -> {
            needMiner();
            step(t += 10, "hover", null);
        });
        GROUPS.put("update", () -> {
            needMiner();
            // editing in survival with missing materials: Apply is greyed out
            step(t += 5, null, mc -> onServer(mc, sp -> sp.setGameMode(net.minecraft.world.level.GameType.SURVIVAL)));
            step(t += 20, null, DevHooks::aimAtPlaced); // survival: the player dropped, aim again
            step(t += 5, null, mc -> {
                ClientHooks.openScreen();
                if (mc.screen instanceof EditorScreen e) e.devCount(MODIFIERS, ITEM, 4);
            });
            step(t += 40, "edit_apply_missing", null);
            step(t += 5, null, mc -> {
                mc.setScreen(null);
                onServer(mc, sp -> sp.setGameMode(net.minecraft.world.level.GameType.CREATIVE));
            });
            step(t += 10, null, DevHooks::aimAtPlaced);
            step(t += 10, null, mc -> onServer(mc, DevHooks::updateLooked));
            step(t += 15, "updating", null);
            step(t += 110, null, mc -> onServer(mc, sp -> log(sp, "after update")));
            // a second update, cancelled during the wait: nothing changes
            step(t += 5, null, mc -> onServer(mc, DevHooks::updateLooked));
            step(t += 10, null, mc -> onServer(mc, sp -> Formwork.LOG.info("dev: cancel: {}", Updater.cancel(sp))));
            step(t += 50, null, mc -> onServer(mc, sp -> log(sp, "after cancelled update")));
        });
        GROUPS.put("dismantle", () -> {
            needMiner();
            step(t += 10, null, mc -> KeyMapping.set(mc.options.keyUse.getKey(), true));
            step(t += 30, "dismantle_hold", null);
            step(t += 5, "dismantle_sweep", null);
            step(t += 85, null, mc -> {
                KeyMapping.set(mc.options.keyUse.getKey(), false);
                onServer(mc, sp -> log(sp, "after holding use"));
            });
        });
        GROUPS.put("animation", () -> {
            step(t += 10, null, mc -> mc.player.setXRot(-20));
            step(t += 20, null, mc -> record("place"));
            step(t += 1, null, mc -> onServer(mc, DevHooks::placeAnimated));
            step(t += 70, null, mc -> record(null));
            step(t += 5, null, DevHooks::aimAtPlaced);
            step(t += 20, null, mc -> {
                record("dismantle");
                KeyMapping.set(mc.options.keyUse.getKey(), true);
            });
            step(t += 200, null, mc -> {
                KeyMapping.set(mc.options.keyUse.getKey(), false);
                record(null);
                onServer(mc, sp -> log(sp, "after animated dismantle"));
            });
        });
        GROUPS.put("me", () -> {
            // ME range: a powered access point with 8 boosters (~38 blocks with AE2's default config, 16 without)
            step(t += 20, null, mc -> onServer(mc, DevHooks::buildAccessPoint));
            step(t += 60, null, mc -> onServer(mc, sp -> meAt(sp, 10)));
            step(t += 20, null, mc -> onServer(mc, sp -> meAt(sp, 30)));
            step(t += 20, null, mc -> onServer(mc, sp -> meAt(sp, 80)));
            // AEInfinityBooster: an infinity card in the access point instead of boosters (only if the mod is in the dev run)
            step(t += 20, null, mc -> onServer(mc, DevHooks::infinityCard));
            step(t += 60, null, mc -> onServer(mc, sp -> meAt(sp, 80)));
            // AE2's own craft screen from the wand, next to the access point
            step(t += 10, null, mc -> {
                editor(mc, FISSION, "MATERIALS", null);
                ClientHooks.resume = (EditorScreen) mc.screen;
            });
            step(t += 20, null, mc -> onServer(mc, sp -> {
                meAt(sp, 5);
                dev.sylviromi.formwork.ae2.AeLink.openCraft(sp, net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.Items.STONE, 64);
            }));
            step(t += 40, "ae2_craft", null);
            // AE2's Back (and Start) return through the host: the editor comes back
            step(t += 5, null, mc -> onServer(mc, DevHooks::aeBack));
            step(t += 30, "ae2_back", null);
            step(t += 1, null, mc -> Formwork.LOG.info("dev: screen after AE2 back: {}", mc.screen));
        });
        GROUPS.put("craft", () -> {
            step(t += 10, null, mc -> onServer(mc, sp -> {
                if (DevHooks.wap == null) buildAccessPoint(sp);
            }));
            step(t += 20, null, mc -> onServer(mc, sp -> meAt(sp, 5)));
            // Craft all as one job: a drive holding the fission reactor's glass and casing, then the structure job's AE2 plan, started
            step(t += 5, null, mc -> onServer(mc, DevHooks::buildPatterns));
            step(t += 5, null, mc -> onServer(mc, DevHooks::stockReactorBlocks));
            step(t += 10, null, mc -> onServer(mc, DevHooks::stockReactorBlocks)); // the new drive joins the grid a tick later
            step(t += 20, null, mc -> {
                editor(mc, FISSION, "MATERIALS", null);
                ClientHooks.resume = (EditorScreen) mc.screen;
            });
            step(t += 40, "craft_all_button", null);
            step(t += 5, null, mc -> onServer(mc, sp -> dev.sylviromi.formwork.ae2.AeLink.craftStructure(sp, net.minecraft.world.InteractionHand.MAIN_HAND,
                    Map.of(reactorBlock("reactor_glass"), 146, reactorBlock("fission_reactor_casing"), 68), net.minecraft.network.chat.Component.literal("Fission Reactor"))));
            step(t += 60, "craft_all_confirm", null);
            step(t += 1, null, mc -> Formwork.LOG.info("dev: screen after craft all: {}", mc.screen));
            step(t += 5, null, mc -> onServer(mc, sp -> {
                if (sp.containerMenu instanceof appeng.menu.me.crafting.CraftConfirmMenu m) m.startJob();
                else Formwork.LOG.warn("dev: no AE2 plan open: {}", sp.containerMenu);
            }));
            // a stand-in machine: a chest takes the cobblestone the provider pushes, and a few blocks per step are handed back to the job as its output
            step(t += 5, null, mc -> onServer(mc, DevHooks::addChestMachine));
            for (int i = 0; i < 8; i++) {
                int n = i;
                step(t += 25, null, mc -> onServer(mc, sp -> {
                    DevHooks.machineOutput(sp, 18, 8);
                    Formwork.LOG.info("dev: craft all progress (percent/seconds left per block): {}",
                            dev.sylviromi.formwork.ae2.AeLink.snapshot(sp, sp.getMainHandItem(), List.of(reactorBlock("reactor_glass"), reactorBlock("fission_reactor_casing")))
                                    .values().stream().map(v -> v[2] + "%/" + v[3] + "s").toList());
                }));
                if (n == 1 || n == 3 || n == 5) step(t += 6, "craft_progress_" + n, null);
            }
            step(t += 60, null, mc -> onServer(mc, sp -> Formwork.LOG.info("dev: after the structure job: ME {} (glass, casing)",
                    dev.sylviromi.formwork.ae2.AeLink.snapshot(sp, sp.getMainHandItem(), List.of(reactorBlock("reactor_glass"), reactorBlock("fission_reactor_casing"))).values().stream().map(v -> v[0]).toList())));
            step(t += 1, null, mc -> Formwork.LOG.info("dev: screen after the structure job: {}", mc.screen));
            // AE2's own path for comparison: amount screen, then Next
            step(t += 5, null, mc -> onServer(mc, sp -> dev.sylviromi.formwork.ae2.AeLink.openCraft(sp, net.minecraft.world.InteractionHand.MAIN_HAND,
                    ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("mekanismgenerators:reactor_glass")), 146)));
            step(t += 10, null, mc -> onServer(mc, sp -> { if (sp.containerMenu instanceof appeng.menu.me.crafting.CraftAmountMenu m) m.confirm(146, false, false); }));
            step(t += 40, "ae2_own_plan", null);
        });
        GROUPS.put("memory", () -> {
            // remembered settings: pick something, reopen the editor, and read it back; then a favourite and the browser order
            step(t += 10, null, mc -> {
                editor(mc, VOID_MINER, "VARIANTS", e -> {
                    e.devTier(8);
                    e.devCount(MODIFIERS, "voidminers:verdium_speed_modifier", 3);
                });
                mc.setScreen(null);
                Formwork.LOG.info("dev: remembered after closing: {}", Memory.last(VOID_MINER).save());
            });
            step(t += 5, null, mc -> onServer(mc, sp -> PlacerItem.setSelection(sp.getMainHandItem(), Selection.EMPTY)));
            step(t += 10, null, mc -> {
                editor(mc, VOID_MINER, "VARIANTS", null);
                Formwork.LOG.info("dev: remembered selection restored in the editor: {}", mc.screen instanceof EditorScreen e ? e.selection().save() : "no editor");
            });
            step(t += 40, "memory_restored", null);
            step(t += 5, null, mc -> {
                if (Memory.favourite(TURBINE)) Memory.toggleFavourite(TURBINE);
                mc.setScreen(new BrowserScreen());
            });
            step(t += 40, "memory_before", null);
            step(t += 5, null, mc -> Memory.toggleFavourite(TURBINE));
            step(t += 5, null, mc -> mc.setScreen(new BrowserScreen()));
            step(t += 40, "memory_favourite", null);
            step(t += 5, null, mc -> Formwork.LOG.info("dev: favourite stored: {}", Memory.favourite(TURBINE)));
        });
        GROUPS.put("stateful", () -> {
            // mirror and rotate stairs and a lever, and read the states back
            step(t += 10, null, mc -> {
                var specs = new java.util.TreeMap<BlockPos, dev.sylviromi.formwork.structure.BlockSpec>();
                specs.put(new BlockPos(0, 0, 0), new dev.sylviromi.formwork.structure.BlockSpec("minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom", "shape", "straight"), null));
                specs.put(new BlockPos(2, 0, 0), new dev.sylviromi.formwork.structure.BlockSpec("minecraft:lever", Map.of("face", "wall", "facing", "east"), null));
                specs.put(new BlockPos(1, 0, 1), new dev.sylviromi.formwork.structure.BlockSpec("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom", "shape", "inner_left"), null));
                for (boolean m : new boolean[]{false, true})
                    for (Direction f : new Direction[]{Direction.NORTH, Direction.EAST})
                        Resolver.world(specs, BlockPos.ZERO, f, m).forEach((pos, b) -> Formwork.LOG.info("dev: stateful mirror={} facing={} {} -> {}", m, f, pos, b.state()));
            });
        });
        GROUPS.put("night", () -> {
            step(t += 10, null, mc -> onServer(mc, sp -> sp.serverLevel().setDayTime(18000)));
            step(t += 20, null, mc -> mc.player.setXRot(-20));
            step(t += 5, null, mc -> record("night"));
            step(t += 1, null, mc -> onServer(mc, DevHooks::placeAnimated));
            step(t += 70, null, mc -> record(null));
            step(t += 5, null, mc -> onServer(mc, sp -> sp.serverLevel().setDayTime(6000)));
        });
        GROUPS.put("midgrow", () -> {
            // dismantling while the placement is still growing in: nothing may stay behind
            step(t += 10, null, mc -> onServer(mc, sp -> {
                for (var r : new java.util.ArrayList<>(Placements.of(sp.serverLevel()).all())) Dismantler.dismantle(sp, sp.getMainHandItem(), r);
            }));
            step(t += 20, null, mc -> onServer(mc, DevHooks::placeAnimated));
            step(t += 4, null, mc -> onServer(mc, sp -> new java.util.ArrayList<>(Placements.of(sp.serverLevel()).all()).forEach(r -> Dismantler.dismantle(sp, sp.getMainHandItem(), r))));
            step(t += 80, null, mc -> onServer(mc, sp -> log(sp, "after dismantling mid-grow")));
        });
        GROUPS.put("jei", () -> {
            // JEI: every structure, then the ones using reactor glass
            step(t += 5, null, mc -> mc.setScreen(null));
            step(t += 5, null, mc -> JeiStructures.devShow(null));
            step(t += 40, "jei_structures", null);
            step(t += 5, null, mc -> JeiStructures.devShow(new ItemStack(reactorBlock("reactor_glass"))));
            step(t += 40, "jei_uses", null);
            step(t += 5, null, mc -> JeiStructures.devShow(null));
            step(t += 30, null, mc -> moveMouse(mc, 912, 500));
            step(t += 20, "jei_hover", null);
            step(t += 5, null, mc -> moveMouse(mc, 912, 520));
            step(t += 20, "jei_hover2", null);
            step(t += 5, null, mc -> moveMouse(mc, 703, 760));
            step(t += 20, "jei_material_casing", null);
            step(t += 5, null, mc -> moveMouse(mc, 943, 760));
            step(t += 20, "jei_material_last", null);
        });
    }

    /** Builds the steps of the groups asked for in -Pmbdev (all when none are named). */
    private static void plan() {
        Set<String> wanted = PROPERTY.equals("true") || PROPERTY.equals("all") ? GROUPS.keySet() : new LinkedHashSet<>(Arrays.asList(PROPERTY.split(",")));
        wanted.stream().filter(g -> !GROUPS.containsKey(g)).forEach(g -> Formwork.LOG.warn("dev: no group {}, known: {}", g, GROUPS.keySet()));
        step(20, null, mc -> onServer(mc, DevHooks::reset));
        t = 20;
        GROUPS.forEach((name, steps) -> {
            if (wanted.contains(name)) steps.run();
        });
        step(t += 20, null, Minecraft::stop);
    }

    /** Places the tier 9 void miner and looks at it, unless a placement exists already (an earlier group). */
    private static void needMiner() {
        step(t += 10, null, mc -> onServer(mc, sp -> {
            if (Placements.of(sp.serverLevel()).all().isEmpty()) placeVoidMiner(mc, sp);
        }));
        step(t += 40, null, DevHooks::aimAtPlaced);
        step(t += 10, null, DevHooks::aimAtPlaced);
    }

    private DevHooks() {
    }

    private static void editor(Minecraft mc, String key, String tab, Consumer<EditorScreen> setup) {
        Catalog.client.entries().stream().filter(en -> en.key().equals(key)).findFirst().ifPresentOrElse(en -> {
            EditorScreen e = new EditorScreen(en);
            mc.setScreen(e);
            e.showTab(tab);
            if (setup != null) setup.accept(e);
        }, () -> Formwork.LOG.warn("dev: entry {} not in the catalog", key));
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (!ON || e.phase != TickEvent.Phase.END || mc.player == null) return;
        if (tick++ == 0) {
            plan();
            if (SLOW > 1) slowClient(mc);
        }
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
        sp.getInventory().setItem(sp.getInventory().selected, new ItemStack(Formwork.PLACER.get()));
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

    /** Server: a tier 9 void miner with 4 speed modifiers 3 blocks ahead (with a record). */
    private static void placeVoidMiner(Minecraft mc, ServerPlayer sp) {
        Direction f = sp.getDirection();
        Selection sel = new Selection(tier9(Catalog.server), new int[0], Map.of(), modifiers(SPEED, 4));
        var specs = Resolver.specs(Catalog.server, sel);
        BlockPos target = Resolver.centerFor(Resolver.Footprint.of(specs.keySet()), sp.blockPosition().relative(f, 3), f, f);
        var blocks = Resolver.world(specs, target, f, false);
        Placer.put(sp.serverLevel(), blocks, sp);
        Placements.of(sp.serverLevel()).add(sp.serverLevel(), Placements.record(blocks, sel.save(), target, f, false, sp.getUUID()));
        log(sp, "placed at " + target + " facing " + f);
        mc.execute(() -> mc.player.setXRot(15));
    }

    private static final String[] NC = {"msr", "fission_reactor", "heat_exchanger", "turbine"};

    /** Server: the nth NuclearCraft structure at its default size, 40 blocks apart in a row, on cleared ground. */
    private static void placeNuclearCraft(ServerPlayer sp, int n) {
        String id = "formwork:gen/nuclearcraft_" + NC[n];
        Direction f = Direction.NORTH;
        Selection sel = new Selection(id, new int[0], Map.of());
        var specs = Resolver.specs(Catalog.server, sel);
        if (specs == null) {
            Formwork.LOG.warn("dev: nc: no structure {}", id);
            return;
        }
        BlockPos at = sp.blockPosition().offset(n * 40 - 60, 0, -30);
        var src = sp.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        sp.server.getCommands().performPrefixedCommand(src, "fill " + (at.getX() - 20) + " " + at.getY() + " " + (at.getZ() - 20) + " " + (at.getX() + 20) + " " + (at.getY() + 25) + " " + (at.getZ() + 20) + " air");
        BlockPos target = Resolver.centerFor(Resolver.Footprint.of(specs.keySet()), at, f, f);
        var blocks = Resolver.world(specs, target, f, false);
        Placer.put(sp.serverLevel(), blocks, sp);
        Placements.of(sp.serverLevel()).add(sp.serverLevel(), Placements.record(blocks, sel.save(), target, f, false, sp.getUUID()));
        Formwork.LOG.info("dev: nc: placed {} at {} ({} blocks)", id, target, blocks.size());
    }

    /** Server: asks every NuclearCraft controller to validate again (invalidateCache, by reflection: the mod is runtime only). */
    private static void kickNuclearCraft(ServerPlayer sp) {
        ServerLevel level = sp.serverLevel();
        for (var r : Placements.of(level).all())
            for (BlockPos p : Placements.positions(r, Catalog.server)) {
                var be = level.getBlockEntity(p);
                if (be == null || !level.getBlockState(p).getBlock().getDescriptionId().contains("controller")) continue;
                try {
                    be.getClass().getMethod("invalidateCache").invoke(be);
                } catch (ReflectiveOperationException e) {
                    Formwork.LOG.warn("dev: nc: cannot kick {}", p, e);
                }
            }
    }

    /** Server: every block entity of the placed structures whose data mentions forming, one log line each. */
    private static void reportNuclearCraft(ServerPlayer sp) {
        ServerLevel level = sp.serverLevel();
        for (var r : Placements.of(level).all())
            for (BlockPos p : Placements.positions(r, Catalog.server)) {
                var be = level.getBlockEntity(p);
                if (be == null || !level.getBlockState(p).getBlock().getDescriptionId().contains("controller")) continue;
                var info = be.saveWithoutMetadata().getCompound("Info");
                Formwork.LOG.info("dev: nc: controller {} at {}: casing {} internal {} validations {} size {}x{}x{} error {} formed-ish {}", level.getBlockState(p).getBlock().getDescriptionId(), p,
                        info.getBoolean("isCasingValid"), info.getBoolean("isInternalValid"), info.getLong("validationsCounter"), info.getInt("width"), info.getInt("height"), info.getInt("depth"),
                        BlockPos.of(info.getLong("erroredBlock")), info.getLong("multiblockTicksCounter"));
            }
    }

    /** Server: the same miner placed the way a player does, so it appears as a sweep. */
    private static void placeAnimated(ServerPlayer sp) {
        Direction f = sp.getDirection();
        ItemStack tool = sp.getMainHandItem();
        PlacerItem.setSelection(tool, new Selection(tier9(Catalog.server), new int[0], Map.of(), modifiers(SPEED, 4)));
        var specs = Resolver.specs(Catalog.server, PlacerItem.selection(tool));
        Placer.place(sp, tool, Resolver.centerFor(Resolver.Footprint.of(specs.keySet()), sp.blockPosition().relative(f, 8), f, f), f, false, false);
    }

    /** Puts the mouse at window pixel x, y: sets what the game reads as the cursor position (dev run only). */
    private static void moveMouse(Minecraft mc, double x, double y) {
        try {
            for (var f : new String[][]{{"xpos", "" + x}, {"ypos", "" + y}}) {
                var field = net.minecraft.client.MouseHandler.class.getDeclaredField(f[0]);
                field.setAccessible(true);
                field.setDouble(mc.mouseHandler, Double.parseDouble(f[1]));
            }
        } catch (ReflectiveOperationException e) {
            Formwork.LOG.warn("dev: cannot move the mouse", e);
        }
    }

    private static void setMirror(boolean on) {
        var tool = WorldGhost.heldTool();
        var tag = PlacerItem.placementTag(tool);
        tag.putBoolean(PlacerItem.MIRROR, on);
        ClientHooks.setPlacement(tool, tag);
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
            Formwork.LOG.warn("dev: update: no placement in view");
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
        Formwork.LOG.info("dev: access point at {} with 8 boosters, tool linked", wap.above());
    }

    /** A pattern provider with processing patterns (cobblestone -> reactor glass / fission casing) and a crafting CPU, next to the energy cell. */
    private static void buildPatterns(ServerPlayer sp) {
        var src = sp.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        var cmd = sp.server.getCommands();
        cmd.performPrefixedCommand(src, "setblock " + wap.getX() + " " + wap.getY() + " " + (wap.getZ() + 1) + " ae2:pattern_provider");
        cmd.performPrefixedCommand(src, "setblock " + wap.getX() + " " + wap.getY() + " " + (wap.getZ() - 1) + " ae2:1k_crafting_storage");
        if (sp.level().getBlockEntity(wap.south()) instanceof appeng.blockentity.crafting.PatternProviderBlockEntity pp) {
            for (String out : List.of("mekanismgenerators:reactor_glass", "mekanismgenerators:fission_reactor_casing"))
                pp.getLogic().getPatternInv().addItems(appeng.api.crafting.PatternDetailsHelper.encodeProcessingPattern(
                        new appeng.api.stacks.GenericStack[]{new appeng.api.stacks.GenericStack(appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.COBBLESTONE), 1)},
                        new appeng.api.stacks.GenericStack[]{new appeng.api.stacks.GenericStack(appeng.api.stacks.AEItemKey.of(ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(out))), 1)}));
            pp.getLogic().updatePatterns();
        }
        Formwork.LOG.info("dev: patterns set up: {}", sp.level().getBlockEntity(wap.south()));
    }

    private static void addChestMachine(ServerPlayer sp) {
        var src = sp.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        sp.server.getCommands().performPrefixedCommand(src, "setblock " + wap.getX() + " " + wap.getY() + " " + (wap.getZ() + 2) + " minecraft:chest");
    }

    /** Hands finished glass and casing back to the running crafting job, as a machine's output would arrive. */
    private static void machineOutput(ServerPlayer sp, int glass, int casing) {
        var grid = dev.sylviromi.formwork.ae2.AeLink.grid(sp, sp.getMainHandItem());
        if (grid == null) return;
        var cs = (appeng.me.service.CraftingService) grid.getCraftingService();
        long g = cs.insertIntoCpus(appeng.api.stacks.AEItemKey.of(reactorBlock("reactor_glass")), glass, appeng.api.config.Actionable.MODULATE);
        long c = cs.insertIntoCpus(appeng.api.stacks.AEItemKey.of(reactorBlock("fission_reactor_casing")), casing, appeng.api.config.Actionable.MODULATE);
        Formwork.LOG.info("dev: machine output accepted by the job: {} glass, {} casing", g, c);
    }

    private static net.minecraft.world.item.Item reactorBlock(String name) {
        return ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.tryParse("mekanismgenerators:" + name));
    }

    /** A drive with a cell next to the energy cell, holding enough glass and casing for the default fission reactor. */
    private static void stockReactorBlocks(ServerPlayer sp) {
        var src = sp.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        sp.server.getCommands().performPrefixedCommand(src, "setblock " + (wap.getX() + 1) + " " + wap.getY() + " " + wap.getZ()
                + " ae2:drive{inv:{item0:{id:\"ae2:item_storage_cell_64k\",Count:1b}}} keep");
        var grid = dev.sylviromi.formwork.ae2.AeLink.grid(sp, sp.getMainHandItem());
        if (grid == null) {
            Formwork.LOG.warn("dev: no grid to stock");
            return;
        }
        var inv = grid.getStorageService().getInventory();
        long cobble = inv.insert(appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.COBBLESTONE), 1000, appeng.api.config.Actionable.MODULATE, appeng.api.networking.security.IActionSource.empty());
        Formwork.LOG.info("dev: stocked {} cobblestone", cobble);
    }

    /** AE2's Back (and Start) return through the host. */
    private static void aeBack(ServerPlayer sp) {
        if (sp.containerMenu instanceof appeng.menu.ISubMenu sub) sub.getHost().returnToMainMenu(sp, sub);
        else Formwork.LOG.warn("dev: no AE2 sub menu open: {}", sp.containerMenu);
    }

    private static void infinityCard(ServerPlayer sp) {
        var src = sp.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        sp.server.getCommands().performPrefixedCommand(src, "setblock " + wap.getX() + " " + (wap.getY() + 1) + " " + wap.getZ()
                + " ae2:wireless_access_point[facing=up]{inv:{item0:{id:\"aeinfinitybooster:infinity_card\",Count:1b}}} replace");
        Formwork.LOG.info("dev: access point now holds an infinity card");
    }

    private static void meAt(ServerPlayer sp, int distance) {
        sp.teleportTo(wap.getX() + distance + 0.5, wap.getY(), wap.getZ() + 0.5);
        var be = sp.level().getBlockEntity(wap.above());
        String info = be instanceof appeng.api.implementations.blockentities.IWirelessAccessPoint w
                ? "grid " + (w.getGrid() != null) + ", active " + w.isActive() + ", range " + w.getRange()
                + (w.getGrid() == null ? "" : ", machines " + w.getGrid().getMachineClasses()) : "not an access point: " + be;
        Formwork.LOG.info("dev: ME at {} blocks: {} ({})", distance, dev.sylviromi.formwork.build.Materials.meStatus(sp, sp.getMainHandItem()), info);
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
        Formwork.LOG.info("dev: {}: {} placements {}, {} blocks in bounds, {} speed / {} item modifiers", what, all.size(),
                all.stream().map(r -> Selection.load(r.selection()).entry() + "@" + r.target()).toList(), blocks, speed, item);
    }

    /** Starts (or with null stops) capturing every rendered frame: frame_<name>_NNNNN.png plus frames_<name>.txt with each frame's game time. */
    private static void record(String name) {
        recording = name;
        recFrames = 0;
        emptySince = -1;
        if (name != null) {
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(Minecraft.getInstance().gameDirectory.getPath(), "screenshots", "frames_" + name + ".txt"));
            } catch (java.io.IOException ignored) {
            }
        }
    }

    @SubscribeEvent
    public static void onRenderFrame(TickEvent.RenderTickEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (!ON || recording == null || e.phase != TickEvent.Phase.END || mc.level == null) return;
        // a dismantle clip ends 70 ticks after the last placement is gone
        if (recording.equals("dismantle") && ClientData.placements.isEmpty()) {
            if (emptySince < 0) emptySince = mc.level.getGameTime();
            else if (mc.level.getGameTime() - emptySince > 70) {
                record(null);
                return;
            }
        }
        String name = "frame_" + recording + "_" + String.format("%05d", recFrames++);
        double time = mc.level.getGameTime() + mc.getPartialTick();
        shot(mc, name);
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(mc.gameDirectory.getPath(), "screenshots", "frames_" + recording + ".txt"),
                    name + " " + time + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ex) {
            Formwork.LOG.warn("dev: frame log: {}", ex.toString());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) throws InterruptedException {
        if (ON && SLOW > 1 && e.phase == TickEvent.Phase.END) Thread.sleep(50L * (SLOW - 1));
    }

    private static void slowClient(Minecraft mc) {
        try {
            Object timer = net.minecraftforge.fml.util.ObfuscationReflectionHelper.getPrivateValue(Minecraft.class, mc, "f_90991_");
            net.minecraftforge.fml.util.ObfuscationReflectionHelper.setPrivateValue(net.minecraft.client.Timer.class, (net.minecraft.client.Timer) timer, 50f * SLOW, "f_92521_");
        } catch (RuntimeException ex) {
            Formwork.LOG.warn("dev: cannot slow the client: {}", ex.toString());
        }
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name.startsWith("frame_") ? name + ".png" : "formwork_" + name + ".png", mc.getMainRenderTarget(), msg -> {
            if (!name.startsWith("frame_")) Formwork.LOG.info("dev screenshot: {}", msg.getString());
        });
    }
}
