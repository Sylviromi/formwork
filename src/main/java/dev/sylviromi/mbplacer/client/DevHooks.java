package dev.sylviromi.mbplacer.client;

import dev.sylviromi.mbplacer.MbPlacer;
import dev.sylviromi.mbplacer.structure.Catalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Dev-only screenshot run, enabled with -Dmbplacer.dev=true (./gradlew runClient -Pmbdev).
 * Opens the picker on a few entries, saves screenshots to run/screenshots, shows the world ghost, then quits.
 */
@Mod.EventBusSubscriber(modid = MbPlacer.ID, value = Dist.CLIENT)
public final class DevHooks {
    private static final boolean ON = Boolean.getBoolean("mbplacer.dev");
    private static final List<String> ENTRIES = List.of("mbtool:voidminer/voidminer", "mbplacer:gen/mekanism_fission_reactor",
            "mbplacer:gen/mekanism_turbine");
    private static int tick;

    private DevHooks() {
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (!ON || e.phase != TickEvent.Phase.END || mc.player == null) return;
        tick++;
        int step = tick / 60, phase = tick % 60;
        if (tick == 20 && mc.getSingleplayerServer() != null) {
            var server = mc.getSingleplayerServer();
            var id = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(id);
                if (sp != null) sp.getInventory().setItem(sp.getInventory().selected, new net.minecraft.world.item.ItemStack(MbPlacer.PLACER.get()));
            });
        }
        if (step >= 1 && step <= ENTRIES.size()) {
            if (phase == 0) {
                PlacerScreen s = new PlacerScreen();
                mc.setScreen(s);
                s.selectForDev(ENTRIES.get(step - 1));
            }
            if (phase == 50) shot(mc, "picker_" + step);
        }
        int after = (ENTRIES.size() + 1) * 60;
        if (tick == after) {
            mc.setScreen(null);
            mc.player.setXRot(55);
        }
        if (tick == after + 40) shot(mc, "ghost");
        if (tick == after + 50) mc.setScreen(new PlacementScreen());
        if (tick == after + 80) shot(mc, "placement");
        if (tick == after + 100) mc.stop();
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "mbplacer_" + name + ".png", mc.getMainRenderTarget(), msg -> MbPlacer.LOG.info("dev screenshot: {}", msg.getString()));
    }

    static boolean known(String key) {
        return Catalog.client.entries().stream().anyMatch(en -> en.key().equals(key));
    }
}
