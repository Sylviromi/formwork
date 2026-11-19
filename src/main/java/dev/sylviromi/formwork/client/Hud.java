package dev.sylviromi.formwork.client;

import dev.sylviromi.formwork.Formwork;
import dev.sylviromi.formwork.build.Placements;
import dev.sylviromi.formwork.item.PlacerItem;
import dev.sylviromi.formwork.net.ClientData;
import dev.sylviromi.formwork.structure.Selection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static dev.sylviromi.formwork.client.Ui.*;

/** Under the crosshair, while pointing at a placed multiblock with the tool: its name, and the dismantle or update progress. */
@Mod.EventBusSubscriber(modid = Formwork.ID, value = Dist.CLIENT)
final class Hud {
    private static final int BAR_W = 64;

    private Hud() {
    }

    /** 0..1 while the use key is held on this placement (dismantling). */
    static float progress(Minecraft mc, Placements.Rec rec) {
        var p = mc.player;
        if (p == null || !p.isUsingItem() || !(p.getUseItem().getItem() instanceof PlacerItem)) return 0;
        return Math.min(1, (p.getTicksUsingItem() + mc.getFrameTime()) / Placements.dismantleTicks(rec));
    }

    /** A running in-place update (server job): its name and progress. */
    private static void updating(GuiGraphics g, Minecraft mc) {
        Placements.Rec rec = ClientData.placements.stream().filter(r -> r.id().equals(ClientData.progressFor)).findFirst().orElse(null);
        // full only once the server says it happened (Progress 0 hides the bar)
        float progress = Math.min(0.98f, Math.max(0.01f, (float) (System.currentTimeMillis() - ClientData.progressStart) / ClientData.progressMillis));
        label(g, mc, rec == null ? Component.empty() : PlacerItem.displayName(Selection.load(rec.selection()).entry()), ACCENT, progress, ACCENT);
    }

    /** The name under the crosshair, like vanilla's subtitle text, with a thin bar under it while something runs. */
    private static void label(GuiGraphics g, Minecraft mc, Component name, int color, float progress, int barColor) {
        int cx = g.guiWidth() / 2, y = g.guiHeight() / 2 + 10;
        g.drawCenteredString(mc.font, name, cx, y, color);
        if (progress <= 0) return;
        g.fill(cx - BAR_W / 2, y + 11, cx + BAR_W / 2, y + 13, 0x80000000);
        g.fill(cx - BAR_W / 2, y + 11, cx - BAR_W / 2 + (int) (BAR_W * progress), y + 13, barColor);
    }

    @SubscribeEvent
    static void onOverlay(RenderGuiOverlayEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (e.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type() || mc.player == null || mc.screen != null) return;
        GuiGraphics g = e.getGuiGraphics();
        if (ClientData.progressFor != null) {
            updating(g, mc);
            return;
        }
        ItemStack tool = WorldGhost.heldTool();
        Placements.Rec rec = tool.isEmpty() ? null : PlacerItem.placementLookedAt(mc.player);
        if (rec == null) return;
        float progress = progress(mc, rec);
        label(g, mc, PlacerItem.displayName(Selection.load(rec.selection()).entry()), progress > 0 ? RED : ACCENT, progress, RED);
    }
}
