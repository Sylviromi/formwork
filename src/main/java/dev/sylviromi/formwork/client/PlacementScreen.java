package dev.sylviromi.formwork.client;

import dev.sylviromi.formwork.item.PlacerItem;
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

import static dev.sylviromi.formwork.client.Ui.*;

/**
 * Compact see-through placement card: anchor, facing compass, nudge pad. The ghost moves live behind it.
 * Arrow keys move relative to where the player looks, Page Up/Down vertically, R turns. Shift moves by 10.
 */
public class PlacementScreen extends Screen {
    /** B: a button; GRID: a 3x3 block of them GAP apart (the compass and the move pad). */
    private static final int W = 134, B = CTRL, GRID = 3 * B + 2 * GAP;
    private final List<Btn> buttons = new ArrayList<>();
    private int x0, y0, h;

    private static final int NORMAL = 0, ANCHOR = 2;

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
        super(Component.translatable("formwork.gui.placement"));
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
                    : Direction.NORTH;
            t.putInt(PlacerItem.ROTATION, dev.sylviromi.formwork.build.Resolver.turnsTo(base, d));
        });
    }

    private void mirror() {
        edit(t -> t.putBoolean(PlacerItem.MIRROR, !t.getBoolean(PlacerItem.MIRROR)));
    }

    private Direction look() {
        return ClientHooks.facing;
    }

    @Override
    protected void init() {
        buttons.clear();
        int gridY = PAD + B + PAD, lowY = gridY + GRID + PAD;
        h = lowY + B + INSET + 9 + PAD; // the last line is the offset readout
        x0 = MARGIN;
        y0 = (height - h) / 2;
        int x = x0 + PAD, right = x0 + W - PAD, gy = y0 + gridY, ly = y0 + lowY, step = B + GAP;
        icon(right - B, y0 + PAD, B, Icon.ANCHOR, Component.translatable("formwork.gui.anchor_tip"), () -> PlacerItem.anchor(tool()) != null,
                () -> ClientHooks.toggleAnchor(tool()), ANCHOR);
        text(right - 2 * B - GAP, y0 + PAD, B, "M", Component.translatable("formwork.gui.mirror_tip"), () -> PlacerItem.mirror(tool()), this::mirror, NORMAL);
        text(right - 3 * B - 2 * GAP, y0 + PAD, B, "A", Component.translatable("formwork.gui.animate_tip"), () -> PlacerItem.animate(tool()),
                () -> edit(t -> t.putBoolean(PlacerItem.NO_ANIMATION, !t.getBoolean(PlacerItem.NO_ANIMATION))), NORMAL);
        // compass, N up like a map: sets which way the structure faces
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST})
            text(x + (1 + d.getStepX()) * step, gy + (1 + d.getStepZ()) * step, B, d.getName().substring(0, 1).toUpperCase(java.util.Locale.ROOT),
                    Component.translatable("formwork.gui.face", Component.translatable("formwork.dir." + d.getName())), () -> facing() == d, () -> face(d), NORMAL);
        // pad: moves relative to where the player looks
        int px = right - GRID;
        icon(px + step, gy, B, Icon.UP, Component.translatable("formwork.gui.forward"), () -> false, () -> move(look(), step()), NORMAL);
        icon(px + step, gy + 2 * step, B, Icon.DOWN, Component.translatable("formwork.gui.backward"), () -> false, () -> move(look(), -step()), NORMAL);
        icon(px, gy + step, B, Icon.LEFT, Component.translatable("formwork.gui.left"), () -> false, () -> move(look().getCounterClockWise(), step()), NORMAL);
        icon(px + 2 * step, gy + step, B, Icon.RIGHT, Component.translatable("formwork.gui.right"), () -> false, () -> move(look().getClockWise(), step()), NORMAL);
        // under the pad: up and down share its width; under the compass: reset
        int uw = (GRID - GAP) / 2;
        icon(px, ly, uw, Icon.TOP, Component.translatable("formwork.gui.up"), () -> false, () -> move(Direction.UP, step()), NORMAL);
        icon(px + uw + GAP, ly, GRID - uw - GAP, Icon.BOTTOM, Component.translatable("formwork.gui.down"), () -> false, () -> move(Direction.DOWN, step()), NORMAL);
        text(x, ly, GRID, Component.translatable("controls.reset").getString(), Component.translatable("formwork.gui.reset"), () -> false, () -> edit(t -> {
            t.putIntArray(PlacerItem.OFFSET, new int[3]);
            t.putInt(PlacerItem.ROTATION, 0);
            t.putBoolean(PlacerItem.MIRROR, false);
        }), NORMAL);
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
        g.drawString(font, title, x0 + PAD, textY(y0 + PAD, B), ACCENT, false);
        int[] o = PlacerItem.offset(tool());
        g.drawCenteredString(font, "x " + o[0] + "  y " + o[1] + "  z " + o[2], x0 + W / 2, y0 + h - PAD - 8, DIM);
        for (Btn b : buttons) {
            boolean hover = b.over(mx, my), on = b.on().getAsBoolean();
            int fill = !on ? (hover ? BTN_HOVER : BTN) : b.style() == ANCHOR ? 0xFF6B5A1F : BTN_ON;
            int edge = on ? (b.style() == ANCHOR ? YELLOW : ACCENT) : hover ? ACCENT : BORDER;
            g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + B, fill);
            g.renderOutline(b.x(), b.y(), b.w(), B, edge);
            int color = on && b.style() == ANCHOR ? YELLOW : hover ? 0xFFFFFFFF : TEXT;
            if (b.icon() != null) Ui.icon(g, b.icon(), b.x() + (b.w() - 9) / 2, b.y() + (B - 9) / 2, color);
            else g.drawCenteredString(font, b.label(), b.x() + (b.w() + 1) / 2, textY(b.y(), B), color);
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
            case GLFW.GLFW_KEY_M -> mirror();
            default -> {
                if (ClientHooks.PLACEMENT.matches(key, scan)) {
                    onClose();
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
