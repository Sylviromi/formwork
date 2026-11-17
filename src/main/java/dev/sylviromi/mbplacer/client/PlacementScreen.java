package dev.sylviromi.mbplacer.client;

import dev.sylviromi.mbplacer.item.PlacerItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static dev.sylviromi.mbplacer.client.Ui.*;

/**
 * Compact see-through placement card: anchor, facing compass, nudge pad, mode. The ghost moves live behind it.
 * Arrow keys move relative to where the player looks, Page Up/Down vertically, R turns. Shift moves by 10.
 */
public class PlacementScreen extends Screen {
    private static final int W = 134, B = 15;
    private final List<Btn> buttons = new ArrayList<>();
    private int x0, y0, h;

    private static final int NORMAL = 0, DANGER = 1, ANCHOR = 2;

    /** A button with a text label or an icon; on() highlights it in its style's colour. */
    private record Btn(int x, int y, int w, String label, Icon icon, Component tip, BooleanSupplier on, Runnable action, int style) {
        boolean over(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + B;
        }
    }

    private void text(int x, int y, int w, String label, Component tip, BooleanSupplier on, Runnable action, int style) {
        buttons.add(new Btn(x, y, w, label, null, tip, on, action, style));
    }

    private void icon(int x, int y, int w, Icon icon, Component tip, BooleanSupplier on, Runnable action, int style) {
        buttons.add(new Btn(x, y, w, null, icon, tip, on, action, style));
    }

    public PlacementScreen() {
        super(Component.translatable("mbplacer.gui.placement"));
    }

    private static ItemStack tool() {
        return WorldGhost.heldTool();
    }

    private static void edit(Consumer<CompoundTag> change) {
        ItemStack tool = tool();
        if (tool.isEmpty()) return;
        CompoundTag t = PlacerItem.placementTag(tool);
        change.accept(t);
        ClientHooks.setPlacement(tool, t);
    }

    private void move(Direction d, int by) {
        edit(t -> {
            int[] o = t.getIntArray(PlacerItem.OFFSET);
            o[0] += d.getStepX() * by;
            o[1] += d.getStepY() * by;
            o[2] += d.getStepZ() * by;
            t.putIntArray(PlacerItem.OFFSET, o);
        });
    }

    private static int step() {
        return hasShiftDown() ? 10 : 1;
    }

    /** Facing the structure would be placed with now. */
    private static Direction facing() {
        PlacerItem.Placement p = ClientHooks.currentPlacement(tool());
        return p == null ? Direction.NORTH : p.facing();
    }

    private static void face(Direction d) {
        edit(t -> {
            Direction base = t.contains(PlacerItem.ANCHOR) ? Direction.from2DDataValue(t.getInt(PlacerItem.ANCHOR_FACING))
                    : net.minecraft.client.Minecraft.getInstance().player.getDirection();
            t.putInt(PlacerItem.ROTATION, dev.sylviromi.mbplacer.build.Resolver.turnsTo(base, d));
        });
    }

    private Direction look() {
        return minecraft.player.getDirection();
    }

    @Override
    protected void init() {
        buttons.clear();
        h = 124;
        x0 = 8;
        y0 = (height - h) / 2;
        int x = x0 + 6, right = x0 + W - 6;
        icon(right - B, y0 + 4, B, Icon.ANCHOR, Component.translatable("mbplacer.gui.anchor_tip"), () -> PlacerItem.anchor(tool()) != null,
                () -> ClientHooks.toggleAnchor(tool()), ANCHOR);
        // compass, N up like a map: sets which way the structure faces
        int cx = x + 22, cy = y0 + 44, half = B / 2;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST})
            text(cx - half + d.getStepX() * 15, cy - half + d.getStepZ() * 15, B, d.getName().substring(0, 1).toUpperCase(java.util.Locale.ROOT),
                    Component.translatable("mbplacer.gui.face", Component.translatable("mbplacer.dir." + d.getName())), () -> facing() == d, () -> face(d), NORMAL);
        // pad: moves relative to where the player looks
        int pw = 3 * B + 4, px = right - pw, pcx = px + B + 2, pcy = cy - half;
        icon(pcx, pcy - B - 2, B, Icon.UP, Component.translatable("mbplacer.gui.forward"), () -> false, () -> move(look(), step()), NORMAL);
        icon(pcx, pcy + B + 2, B, Icon.DOWN, Component.translatable("mbplacer.gui.backward"), () -> false, () -> move(look(), -step()), NORMAL);
        icon(px, pcy, B, Icon.LEFT, Component.translatable("mbplacer.gui.left"), () -> false, () -> move(look().getCounterClockWise(), step()), NORMAL);
        icon(px + 2 * B + 4, pcy, B, Icon.RIGHT, Component.translatable("mbplacer.gui.right"), () -> false, () -> move(look().getClockWise(), step()), NORMAL);
        int uy = cy + 28, vw = (pw - 2) / 2;
        icon(px, uy, vw, Icon.TOP, Component.translatable("mbplacer.gui.up"), () -> false, () -> move(Direction.UP, step()), NORMAL);
        icon(right - vw, uy, vw, Icon.BOTTOM, Component.translatable("mbplacer.gui.down"), () -> false, () -> move(Direction.DOWN, step()), NORMAL);
        text(x, uy, cx + 22 + half - x, Component.translatable("controls.reset").getString(), Component.translatable("mbplacer.gui.reset"), () -> false, () -> edit(t -> {
            t.putIntArray(PlacerItem.OFFSET, new int[3]);
            t.putInt(PlacerItem.ROTATION, 0);
        }), NORMAL);
        int my = y0 + h - B - 6, mw = (W - 14) / 2;
        text(x, my, mw, Component.translatable("mbplacer.gui.place").getString(), Component.translatable("mbplacer.gui.place_tip"),
                () -> !PlacerItem.dismantle(tool()), () -> edit(t -> t.putBoolean(PlacerItem.DISMANTLE, false)), NORMAL);
        text(right - mw, my, mw, Component.translatable("mbplacer.gui.dismantle").getString(), Component.translatable("mbplacer.gui.dismantle_tip"),
                () -> PlacerItem.dismantle(tool()), () -> edit(t -> t.putBoolean(PlacerItem.DISMANTLE, true)), DANGER);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        if (tool().isEmpty()) {
            onClose();
            return;
        }
        // no background: the world and the ghost stay visible
        g.fill(x0, y0, x0 + W, y0 + h, BG);
        g.renderOutline(x0, y0, W, h, BORDER);
        g.drawString(font, title, x0 + 6, y0 + 8, ACCENT, false);
        int[] o = PlacerItem.offset(tool());
        g.drawCenteredString(font, "x " + o[0] + "  y " + o[1] + "  z " + o[2], x0 + W / 2, y0 + 91, DIM);
        for (Btn b : buttons) {
            boolean hover = b.over(mx, my), on = b.on().getAsBoolean();
            int fill = !on ? (hover ? BTN_HOVER : BTN) : b.style() == DANGER ? 0xFF6B2B2B : b.style() == ANCHOR ? 0xFF6B5A1F : BTN_ON;
            int edge = on ? (b.style() == DANGER ? RED : b.style() == ANCHOR ? YELLOW : ACCENT) : hover ? ACCENT : BORDER;
            g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + B, fill);
            g.renderOutline(b.x(), b.y(), b.w(), B, edge);
            int color = on && b.style() == ANCHOR ? YELLOW : hover ? 0xFFFFFFFF : TEXT;
            if (b.icon() != null) Ui.icon(g, b.icon(), b.x() + (b.w() - 9) / 2, b.y() + (B - 9) / 2, color);
            else g.drawCenteredString(font, b.label(), b.x() + (b.w() + 1) / 2, b.y() + (B - 7) / 2, color);
            if (hover) setTooltipForNextRenderPass(b.tip());
        }
        super.render(g, mx, my, partial);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (Btn b : buttons)
            if (b.over(mx, my)) {
                b.action().run();
                return true;
            }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        switch (key) {
            case GLFW.GLFW_KEY_UP -> move(look(), step());
            case GLFW.GLFW_KEY_DOWN -> move(look(), -step());
            case GLFW.GLFW_KEY_LEFT -> move(look().getCounterClockWise(), step());
            case GLFW.GLFW_KEY_RIGHT -> move(look().getClockWise(), step());
            case GLFW.GLFW_KEY_PAGE_UP -> move(Direction.UP, step());
            case GLFW.GLFW_KEY_PAGE_DOWN -> move(Direction.DOWN, step());
            case GLFW.GLFW_KEY_R -> face(facing().getClockWise());
            default -> {
                if (ClientHooks.PLACEMENT.matches(key, scan)) {
                    onClose();
                    return true;
                }
                if (ClientHooks.ANCHOR.matches(key, scan)) {
                    ClientHooks.toggleAnchor(tool());
                    return true;
                }
                return super.keyPressed(key, scan, mods);
            }
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
