package dev.sylviromi.mbplacer.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import dev.sylviromi.mbplacer.MbPlacer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.Collection;

/** Shared look of the screens: palette, flat buttons, and the 3D structure view. */
final class Ui {
    static final int BG = 0xE00D1117, PANEL = 0xF0161B22, BORDER = 0xFF30363D, ACCENT = 0xFF4FD1FF, TEXT = 0xFFE6EDF3, DIM = 0xFF8B949E,
            HOVER = 0x2A4FD1FF, RED = 0xFFFF6B6B, GREEN = 0xFF6BE58A, YELLOW = 0xFFFFD166, VIEW = 0xF0090C10, CELL = 0xFF1B222B,
            BTN = 0xFF1F4E5F, BTN_HOVER = 0xFF24596B, BTN_ON = 0xFF2B6F87;

    private Ui() {
    }

    static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 9x9 pixel icons in textures/gui/icons.png, one per 16px cell, drawn white and tinted. */
    enum Icon {UP, DOWN, LEFT, RIGHT, CCW, CW, ANCHOR, TOP, BOTTOM}

    private static final ResourceLocation ICONS = new ResourceLocation(MbPlacer.ID, "textures/gui/icons.png");

    static void icon(GuiGraphics g, Icon icon, int x, int y, int color) {
        g.setColor((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f);
        g.blit(ICONS, x, y, icon.ordinal() * 16, 0, 9, 9, Icon.values().length * 16, 16);
        g.setColor(1, 1, 1, 1);
    }

    /** Button frame: lighter with an accent edge on hover, accent when on. Returns whether the mouse is over it. */
    static boolean frame(GuiGraphics g, int x, int y, int w, int h, boolean on, int mx, int my) {
        boolean hover = in(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, on ? BTN_ON : hover ? BTN_HOVER : BTN);
        g.renderOutline(x, y, w, h, on || hover ? ACCENT : BORDER);
        return hover;
    }

    /** Flat text button; returns whether the mouse is over it. */
    static boolean button(GuiGraphics g, Font font, int x, int y, int w, int h, Component label, boolean on, int mx, int my) {
        boolean hover = frame(g, x, y, w, h, on, mx, my);
        g.drawCenteredString(font, label, x + (w + 1) / 2, y + (h - 8) / 2, TEXT);
        return hover;
    }

    /** Flat icon button; use odd sizes so the 9px icon sits exactly in the middle. */
    static boolean button(GuiGraphics g, int x, int y, int w, int h, Icon icon, boolean on, int mx, int my) {
        boolean hover = frame(g, x, y, w, h, on, mx, my);
        icon(g, icon, x + (w - 9) / 2, y + (h - 9) / 2, hover ? 0xFFFFFFFF : TEXT);
        return hover;
    }

    /** Size (in blocks) and centre of a structure; minY is its lowest block. */
    record Bounds(float cx, float cy, float cz, float dx, float dy, float dz, int minY) {
        static final Bounds EMPTY = new Bounds(0.5f, 0.5f, 0.5f, 1, 1, 1, 0);

        static Bounds of(Collection<BlockPos> blocks) {
            if (blocks.isEmpty()) return EMPTY;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : blocks) {
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            }
            return new Bounds((minX + maxX + 1) / 2f, (minY + maxY + 1) / 2f, (minZ + maxZ + 1) / 2f, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, minY);
        }
    }

    /** Camera of a structure view: orbit (yaw, pitch), zoom and pan in pixels. */
    static final class View {
        float yaw = 225, pitch = 30, zoom = 1, panX, panY;
        boolean spin;
        private long last = Util.getMillis();

        void reset() {
            yaw = 225;
            pitch = 30;
            zoom = 1;
            panX = panY = 0;
        }

        void rotate(double dx, double dy) {
            yaw += (float) dx * 1.5f;
            pitch = Math.max(-90, Math.min(90, pitch + (float) dy * 1.5f));
        }

        void zoom(double delta) {
            zoom = (float) Math.max(0.3, Math.min(8, zoom * (delta > 0 ? 1.15 : 1 / 1.15)));
        }

        /**
         * Draws the mesh fitted into the box. dx/dy/dz: structure size in blocks, c*: its centre.
         * Call inside a scissor for the box.
         */
        void draw(GuiGraphics g, StructureMesh mesh, int x, int y, int w, int h, Bounds b) {
            long now = Util.getMillis();
            if (spin) yaw += (now - last) * 0.03f;
            last = now;
            float dx = b.dx(), dy = b.dy(), dz = b.dz();
            g.flush();
            PoseStack ps = g.pose();
            ps.pushPose();
            // fit the structure's screen extent at the current pitch (any yaw)
            float horiz = (float) Math.sqrt(dx * dx + dz * dz), diag = (float) Math.sqrt(horiz * horiz + dy * dy);
            double p = Math.toRadians(pitch);
            float vert = (float) (dy * Math.abs(Math.cos(p)) + horiz * Math.abs(Math.sin(p)));
            float s = Math.min((w - 8) / horiz, (h - 8) / vert) * zoom;
            ps.translate(x + w / 2f + panX, y + h / 2f + panY, 400);
            // the GUI projection is orthographic, so z scale only sets depth: keeps the model within z 300..500 at any zoom
            ps.scale(s, -s, 200f / diag);
            ps.mulPose(Axis.XP.rotationDegrees(pitch));
            ps.mulPose(Axis.YP.rotationDegrees(yaw));
            ps.translate(-b.cx(), -b.cy(), -b.cz());
            RenderSystem.enableDepthTest();
            mesh.draw(new Matrix4f(RenderSystem.getModelViewMatrix()).mul(ps.last().pose()), RenderSystem.getProjectionMatrix());
            ps.popPose();
            // GUI drawn after this (buttons, text, tooltips) must not be hidden behind the model
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        }
    }
}
