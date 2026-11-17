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
import java.util.function.Consumer;
import java.util.function.Supplier;

import static dev.sylviromi.mbplacer.client.PlacerScreen.*;

/**
 * See-through placement panel (like Building Gadgets' paste GUI): anchor, nudge, rotate and mode, with the ghost moving live.
 * Arrow keys move relative to where the player looks, Page Up/Down move vertically, R rotates. Shift moves by 10.
 */
public class PlacementScreen extends Screen {
    private static final int PW = 186, BH = 16;
    private final List<Btn> buttons = new ArrayList<>();
    private int px, py, ph;

    private record Btn(int x, int y, int w, Supplier<Component> label, Runnable action) {
        boolean over(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + BH;
        }
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

    private static void move(int axis, int by) {
        edit(t -> {
            int[] o = t.getIntArray(PlacerItem.OFFSET);
            o[axis] += by;
            t.putIntArray(PlacerItem.OFFSET, o);
        });
    }

    private static void move(Direction d, int by) {
        move(0, d.getStepX() * by);
        move(1, d.getStepY() * by);
        move(2, d.getStepZ() * by);
    }

    private static int step() {
        return hasShiftDown() ? 10 : 1;
    }

    @Override
    protected void init() {
        buttons.clear();
        ph = 22 + 6 * (BH + 4) + 26;
        px = 10;
        py = (height - ph) / 2;
        int x = px + 6, w = PW - 12, y = py + 20;
        buttons.add(new Btn(x, y, w, () -> Component.translatable(PlacerItem.anchor(tool()) == null ? "mbplacer.gui.anchor" : "mbplacer.gui.release"),
                () -> ClientHooks.toggleAnchor(tool())));
        String[] axes = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            int axis = i;
            y += BH + 4;
            buttons.add(new Btn(x + w - 2 * BH - 4, y, BH, () -> Component.literal("-"), () -> move(axis, -step())));
            buttons.add(new Btn(x + w - BH, y, BH, () -> Component.literal("+"), () -> move(axis, step())));
            buttons.add(new Btn(x, y, 0, () -> Component.literal(axes[axis] + "  " + PlacerItem.offset(tool())[axis]), () -> {}));
        }
        y += BH + 4;
        buttons.add(new Btn(x, y, 0, () -> Component.translatable("mbplacer.gui.rotation", PlacerItem.rotation(tool()) * 90), () -> {}));
        buttons.add(new Btn(x + w - 2 * BH - 4, y, BH, () -> Component.literal("⟲"), () -> rotate(-1)));
        buttons.add(new Btn(x + w - BH, y, BH, () -> Component.literal("⟳"), () -> rotate(1)));
        y += BH + 4;
        buttons.add(new Btn(x, y, w, () -> Component.translatable(PlacerItem.dismantle(tool()) ? "mbplacer.gui.mode_dismantle" : "mbplacer.gui.mode_place"),
                () -> edit(t -> t.putBoolean(PlacerItem.DISMANTLE, !t.getBoolean(PlacerItem.DISMANTLE)))));
        y += BH + 4;
        buttons.add(new Btn(x, y, w / 2 - 2, () -> Component.translatable("mbplacer.gui.reset"), () -> edit(t -> {
            t.putIntArray(PlacerItem.OFFSET, new int[3]);
            t.putInt(PlacerItem.ROTATION, 0);
        })));
        buttons.add(new Btn(x + w / 2 + 2, y, w / 2 - 2, () -> Component.translatable("gui.done"), this::onClose));
    }

    private static void rotate(int by) {
        edit(t -> t.putInt(PlacerItem.ROTATION, Math.floorMod(t.getInt(PlacerItem.ROTATION) + by, 4)));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        if (tool().isEmpty()) {
            onClose();
            return;
        }
        // no background: the world and the ghost stay visible
        g.fill(px, py, px + PW, py + ph, BG);
        g.renderOutline(px, py, PW, ph, BORDER);
        g.drawString(font, title, px + 6, py + 6, ACCENT, false);
        for (Btn b : buttons) {
            if (b.w() == 0) {
                g.drawString(font, b.label().get(), b.x(), b.y() + 4, TEXT, false);
                continue;
            }
            boolean hover = b.over(mx, my);
            g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + BH, hover ? 0xFF2B6F87 : 0xFF1F4E5F);
            g.renderOutline(b.x(), b.y(), b.w(), BH, hover ? ACCENT : BORDER);
            g.drawCenteredString(font, b.label().get(), b.x() + b.w() / 2, b.y() + 4, TEXT);
        }
        g.drawString(font, Component.translatable("mbplacer.gui.placement_keys"), px + 6, py + ph - 12, DIM, false);
        super.render(g, mx, my, partial);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (Btn b : buttons)
            if (b.w() > 0 && b.over(mx, my)) {
                b.action().run();
                return true;
            }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        Direction facing = minecraft.player.getDirection();
        switch (key) {
            case GLFW.GLFW_KEY_UP -> move(facing, step());
            case GLFW.GLFW_KEY_DOWN -> move(facing, -step());
            case GLFW.GLFW_KEY_LEFT -> move(facing.getCounterClockWise(), step());
            case GLFW.GLFW_KEY_RIGHT -> move(facing.getClockWise(), step());
            case GLFW.GLFW_KEY_PAGE_UP -> move(1, step());
            case GLFW.GLFW_KEY_PAGE_DOWN -> move(1, -step());
            case GLFW.GLFW_KEY_R -> rotate(1);
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
