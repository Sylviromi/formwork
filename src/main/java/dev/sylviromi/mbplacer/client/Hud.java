package dev.sylviromi.mbplacer.client;

import dev.sylviromi.mbplacer.MbPlacer;
import dev.sylviromi.mbplacer.build.Placements;
import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.net.ClientData;
import dev.sylviromi.mbplacer.structure.Selection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static dev.sylviromi.mbplacer.client.Ui.*;

/** Under the crosshair, while pointing at a placed multiblock with the tool: its name, what you can do, and the dismantle progress. */
@Mod.EventBusSubscriber(modid = MbPlacer.ID, value = Dist.CLIENT)
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

    /** A running in-place update (server job): "Updating <name>" and its progress. */
    private static void updating(GuiGraphics g, Minecraft mc) {
        Placements.Rec rec = ClientData.placements.stream().filter(r -> r.id().equals(ClientData.progressFor)).findFirst().orElse(null);
        Component name = Component.translatable("mbplacer.hud.updating", rec == null ? "" : PlacerItem.displayName(Selection.load(rec.selection()).entry()));
        int cx = g.guiWidth() / 2, y = g.guiHeight() / 2 + 12, w = Math.max(mc.font.width(name), BAR_W) + 10;
        g.fill(cx - w / 2, y - 3, cx + w / 2, y + 17, 0xB00D1117);
        g.renderOutline(cx - w / 2, y - 3, w, 20, BORDER);
        g.drawCenteredString(mc.font, name, cx, y, ACCENT);
        g.fill(cx - BAR_W / 2, y + 11, cx + BAR_W / 2, y + 14, CELL);
        float progress = (float) (System.currentTimeMillis() - ClientData.progressStart) / ClientData.progressMillis;
        g.fill(cx - BAR_W / 2, y + 11, cx - BAR_W / 2 + (int) (BAR_W * Math.min(1, progress)), y + 14, ACCENT);
    }

    @SubscribeEvent
    static void onOverlay(RenderGuiOverlayEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (e.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type() || mc.player == null || mc.screen != null) return;
        GuiGraphics g = e.getGuiGraphics();
        long elapsed = System.currentTimeMillis() - ClientData.progressStart;
        if (ClientData.progressFor != null && elapsed < ClientData.progressMillis) {
            updating(g, mc);
            return;
        }
        ItemStack tool = WorldGhost.heldTool();
        Placements.Rec rec = tool.isEmpty() ? null : PlacerItem.placementLookedAt(mc.player);
        if (rec == null) return;
        var font = mc.font;
        int cx = g.guiWidth() / 2, y = g.guiHeight() / 2 + 12;
        Component name = PlacerItem.displayName(Selection.load(rec.selection()).entry());
        Component hint = Component.translatable("mbplacer.hud.hint");
        int w = Math.max(font.width(name), Math.max(font.width(hint), BAR_W)) + 10;
        float progress = progress(mc, rec);
        int h = progress > 0 ? 30 : 24;
        g.fill(cx - w / 2, y - 3, cx + w / 2, y + h - 3, 0xB00D1117);
        g.renderOutline(cx - w / 2, y - 3, w, h, progress > 0 ? 0xFF7A3434 : BORDER);
        g.drawCenteredString(font, name, cx, y, progress > 0 ? RED : ACCENT);
        g.drawCenteredString(font, hint, cx, y + 11, DIM);
        if (progress > 0) {
            g.fill(cx - BAR_W / 2, y + 21, cx + BAR_W / 2, y + 24, CELL);
            g.fill(cx - BAR_W / 2, y + 21, cx - BAR_W / 2 + (int) (BAR_W * progress), y + 24, RED);
        }
    }
}
