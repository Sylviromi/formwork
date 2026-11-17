package dev.sylviromi.mbplacer.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.sylviromi.mbplacer.build.Materials;
import dev.sylviromi.mbplacer.build.Resolver;
import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.net.ClientData;
import dev.sylviromi.mbplacer.net.Net;
import dev.sylviromi.mbplacer.structure.BlockSpec;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import dev.sylviromi.mbplacer.structure.VariantSet;
import dev.sylviromi.mbplacer.structure.gen.Generator;
import dev.sylviromi.mbplacer.structure.gen.Param;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/** The picker: searchable list (left), 3D preview (centre), settings, variants and materials (right). */
public class PlacerScreen extends Screen {
    // palette
    private static final int BG = 0xE00D1117, PANEL = 0xF0161B22, BORDER = 0xFF30363D, ACCENT = 0xFF4FD1FF,
            TEXT = 0xFFE6EDF3, DIM = 0xFF8B949E, HOVER = 0x2A4FD1FF, RED = 0xFFFF6B6B, GREEN = 0xFF6BE58A, YELLOW = 0xFFFFD166;
    private static final int ROW = 14, LIST_W = 150, SIDE_W = 180, PAD = 6;

    private int x0, y0, w, h;
    private EditBox search;
    private List<Catalog.Entry> filtered = List.of();
    private int listScroll, sideScroll;

    private Catalog.Entry entry;
    private int tier;
    private int[] params = new int[0];
    private final Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();

    private final List<Map.Entry<BlockPos, BlockState>> preview = new ArrayList<>();
    private float cx, cy, cz, size = 1, yaw = 225, pitch = 30, zoom = 1;
    private List<Line> lines = List.of();

    public PlacerScreen() {
        super(Component.translatable("item.mbplacer.placer"));
    }

    // ---------- state ----------

    private Catalog catalog() {
        return Catalog.client;
    }

    private String currentId() {
        return entry == null ? "" : entry.members().get(Math.min(tier, entry.members().size() - 1));
    }

    private Optional<Generator> generator() {
        return catalog().generator(currentId());
    }

    private Selection selection() {
        Map<String, Map<String, Integer>> nonZero = new LinkedHashMap<>();
        counts.forEach((set, m) -> {
            Map<String, Integer> nz = new LinkedHashMap<>();
            m.forEach((b, c) -> { if (c > 0) nz.put(b, c); });
            if (!nz.isEmpty()) nonZero.put(set, nz);
        });
        return new Selection(currentId(), params, nonZero);
    }

    @Override
    protected void init() {
        w = Math.min(width - 16, 620);
        h = Math.min(height - 16, 340);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, x0 + PAD, y0 + PAD, LIST_W - 2 * PAD, 14, Component.translatable("mbplacer.gui.search"));
        search.setHint(Component.translatable("mbplacer.gui.search").withStyle(s -> s.withColor(DIM)));
        search.setValue(query);
        search.setResponder(q -> refilter());
        addRenderableWidget(search);
        if (entry == null) loadFromTool();
        refilter();
        rebuild(entry != null); // also asks the server for a fresh material report
    }

    private void loadFromTool() {
        Selection sel = PlacerItem.selection(WorldGhost.heldTool());
        for (Catalog.Entry e : catalog().entries()) {
            int i = e.members().indexOf(sel.entry());
            if (i >= 0) {
                entry = e;
                tier = i;
                params = sel.params();
                sel.counts().forEach((k, v) -> counts.put(k, new LinkedHashMap<>(v)));
                return;
            }
        }
    }

    private void refilter() {
        String q = search.getValue().toLowerCase(Locale.ROOT);
        filtered = catalog().entries().stream().filter(e -> q.isEmpty() || name(e).getString().toLowerCase(Locale.ROOT).contains(q)
                || e.key().toLowerCase(Locale.ROOT).contains(q)).toList();
        listScroll = 0;
    }

    private Component name(Catalog.Entry e) {
        return PlacerItem.displayName(e.members().size() > 1 ? e.key() : e.members().get(0));
    }

    private void choose(Catalog.Entry e) {
        entry = e;
        tier = 0;
        counts.clear();
        params = generator().map(g -> g.params().stream().mapToInt(Param::def).toArray()).orElse(new int[0]);
        sideScroll = 0;
        rebuild(true);
    }

    /** Recomputes preview and side column; sends the selection to the server when it changed. */
    private void rebuild(boolean send) {
        preview.clear();
        Map<BlockPos, BlockSpec> specs = entry == null ? null : Resolver.specs(catalog(), selection());
        if (specs != null) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (var e : specs.entrySet()) {
                BlockState s = Resolver.state(e.getValue());
                if (s == null) continue;
                BlockPos p = e.getKey();
                preview.add(Map.entry(p, s));
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            }
            cx = (minX + maxX + 1) / 2f;
            cy = (minY + maxY + 1) / 2f;
            cz = (minZ + maxZ + 1) / 2f;
            size = Math.max(1, Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) + 1);
        }
        lines = buildLines(specs);
        if (send && entry != null) Net.CHANNEL.sendToServer(new Net.SetSelection(selection().save()));
    }

    // ---------- right column ----------

    private interface Line {
        default int height() { return ROW; }

        void render(GuiGraphics g, int x, int y, int w, int mx, int my);

        default boolean click(int x, int y, int w, double mx, double my, boolean shift) { return false; }
    }

    private Line header(Component text) {
        return new Line() {
            @Override public int height() { return ROW + 4; }

            @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                g.drawString(font, text, x, y + 6, ACCENT, false);
                g.fill(x, y + 16, x + w, y + 17, BORDER);
            }
        };
    }

    private Line text(Component text, int color) {
        return (g, x, y, w, mx, my) -> g.drawString(font, font.plainSubstrByWidth(text.getString(), w), x, y + 3, color, false);
    }

    /** "label   < value >" with clickable arrows; shift steps by 10. */
    private Line stepper(Component label, Icon icon, java.util.function.Supplier<String> value, java.util.function.IntConsumer step) {
        return new Line() {
            @Override public int height() { return icon == null ? ROW : 18; }

            @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                int ty = y + (height() - 8) / 2;
                int tx = x;
                if (icon != null) {
                    g.renderItem(icon.stack(), x, y + 1);
                    tx += 18;
                }
                String v = value.get();
                int vw = Math.max(18, font.width(v));
                g.drawString(font, font.plainSubstrByWidth(label.getString(), w - (tx - x) - vw - 24), tx, ty, TEXT, false);
                int rx = x + w;
                boolean hoverL = my >= y && my < y + height() && mx >= rx - vw - 20 && mx < rx - vw - 10;
                boolean hoverR = my >= y && my < y + height() && mx >= rx - 10 && mx < rx;
                g.drawString(font, "<", rx - vw - 18, ty, hoverL ? ACCENT : DIM, false);
                g.drawCenteredString(font, v, rx - 10 - vw / 2, ty, TEXT);
                g.drawString(font, ">", rx - 8, ty, hoverR ? ACCENT : DIM, false);
            }

            @Override public boolean click(int x, int y, int w, double mx, double my, boolean shift) {
                int vw = Math.max(18, font.width(value.get()));
                int rx = x + w;
                if (mx >= rx - vw - 20 && mx < rx - vw - 6) step.accept(shift ? -10 : -1);
                else if (mx >= rx - 12 && mx < rx) step.accept(shift ? 10 : 1);
                else return false;
                return true;
            }
        };
    }

    private record Icon(ItemStack stack) {
    }

    private static Icon iconOf(String blockId) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        var block = rl == null ? null : ForgeRegistries.BLOCKS.getValue(rl);
        return block == null ? null : new Icon(new ItemStack(block));
    }

    private List<Line> buildLines(Map<BlockPos, BlockSpec> specs) {
        List<Line> out = new ArrayList<>();
        if (entry == null) {
            out.add(text(Component.literal("Pick a multiblock on the left."), DIM));
            return out;
        }
        Optional<Generator> gen = generator();
        if (gen.isPresent()) {
            out.add(header(Component.translatable("mbplacer.gui.params")));
            List<Param> ps = gen.get().params();
            params = Resolver.clampedParams(gen.get(), params);
            for (int i = 0; i < ps.size(); i++) {
                int idx = i;
                Param p = ps.get(i);
                out.add(stepper(Component.translatable("mbplacer.param." + p.name()), null,
                        () -> p.labels().isEmpty() ? String.valueOf(params[idx]) : p.labels().get(params[idx] - p.min()),
                        d -> { params[idx] = p.clamp(params[idx] + d); rebuild(true); }));
            }
        }
        // variant sets present in the unmodified structure
        Map<BlockPos, BlockSpec> base = Resolver.specs(catalog(), new Selection(currentId(), params, Map.of()));
        if (base != null) {
            Map<VariantSet, Integer> slots = Resolver.slotCounts(catalog(), base);
            if (!slots.isEmpty()) out.add(header(Component.translatable("mbplacer.gui.variants")));
            slots.forEach((set, n) -> {
                Map<String, Integer> c = counts.computeIfAbsent(set.id(), k -> new LinkedHashMap<>());
                int used = c.values().stream().mapToInt(Integer::intValue).sum();
                out.add(text(Component.literal(PlacerItem.prettify(Catalog.shortName(set.id())) + "  " + (n - used) + "/" + n + " default"), DIM));
                for (String block : set.blocks()) {
                    Icon icon = iconOf(block);
                    Component label = icon == null ? Component.literal(block) : icon.stack().getHoverName();
                    out.add(stepper(label, icon, () -> String.valueOf(c.getOrDefault(block, 0)), d -> {
                        int now = c.getOrDefault(block, 0);
                        int free = n - c.values().stream().mapToInt(Integer::intValue).sum();
                        c.put(block, Math.max(0, now + Math.min(d, free)));
                        rebuild(true);
                    }));
                }
            });
        }
        out.add(header(Component.translatable("mbplacer.gui.materials")));
        out.add(text(Component.translatable("mbplacer.gui.me." + ClientData.me.name()), ClientData.me == Materials.MeStatus.CONNECTED ? GREEN : DIM));
        if (!ClientData.reportFor.equals(currentId())) {
            out.add(text(Component.literal("..."), DIM));
            return out;
        }
        boolean craftable = false;
        for (Materials.Row r : ClientData.rows) {
            int have = (int) Math.min(Integer.MAX_VALUE, r.inventory() + Math.max(0, r.me()));
            int color = r.missing() == 0 ? GREEN : r.craftable() ? YELLOW : RED;
            craftable |= r.missing() > 0 && r.craftable();
            ItemStack stack = new ItemStack(r.item());
            out.add(new Line() {
                @Override public int height() { return 18; }

                @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                    g.renderItem(stack, x, y + 1);
                    String count = Math.min(have, r.needed()) + "/" + r.needed();
                    g.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), w - 24 - font.width(count)), x + 18, y + 5, TEXT, false);
                    g.drawString(font, count, x + w - font.width(count), y + 5, color, false);
                    if (mx >= x && mx < x + w && my >= y && my < y + 18)
                        setTooltipForNextRenderPass(Component.literal(stack.getHoverName().getString() + "\nInventory: " + r.inventory()
                                + (r.me() >= 0 ? "\nME: " + r.me() : "") + (r.craftable() ? "\nCraftable" : "")));
                }
            });
        }
        if (craftable && ClientData.me == Materials.MeStatus.CONNECTED) out.add(button(Component.translatable("mbplacer.gui.request_crafts"),
                () -> Net.CHANNEL.sendToServer(new Net.RequestCrafts())));
        return out;
    }

    private Line button(Component label, Runnable action) {
        return new Line() {
            @Override public int height() { return 20; }

            @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                boolean hover = mx >= x && mx < x + w && my >= y + 2 && my < y + 18;
                g.fill(x, y + 2, x + w, y + 18, hover ? 0xFF2B6F87 : 0xFF1F4E5F);
                g.renderOutline(x, y + 2, w, 16, ACCENT);
                g.drawCenteredString(font, label, x + w / 2, y + 6, TEXT);
            }

            @Override public boolean click(int x, int y, int w, double mx, double my, boolean shift) {
                action.run();
                return true;
            }
        };
    }

    // ---------- geometry ----------

    private int listTop() { return y0 + PAD + 18; }
    private int listBottom() { return y0 + h - PAD; }
    private int sideX() { return x0 + w - SIDE_W - PAD; }
    private int sideTop() { return y0 + PAD; }
    private int sideBottom() { return y0 + h - PAD; }
    private int prevX() { return x0 + LIST_W + PAD; }
    private int prevY() { return y0 + PAD + 14; }
    private int prevW() { return sideX() - PAD - prevX(); }
    private int prevH() { return y0 + h - PAD - (entry != null && entry.members().size() > 1 ? 20 : 0) - prevY(); }

    // ---------- rendering ----------

    private String lastReport = "";

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        // rebuild the side column when a new material report arrives
        String rk = ClientData.reportFor + ClientData.rows.hashCode() + ClientData.me;
        if (!rk.equals(lastReport)) {
            lastReport = rk;
            lines = buildLines(entry == null ? null : Resolver.specs(catalog(), selection()));
        }
        renderBackground(g);
        g.fill(x0, y0, x0 + w, y0 + h, BG);
        g.renderOutline(x0, y0, w, h, BORDER);
        renderList(g, mx, my);
        renderPreview(g, mx, my);
        renderSide(g, mx, my);
        super.render(g, mx, my, partial);
    }

    private void renderList(GuiGraphics g, int mx, int my) {
        int x = x0 + PAD, top = listTop(), bottom = listBottom(), lw = LIST_W - 2 * PAD;
        g.fill(x - 2, top - 2, x + lw + 2, bottom + 2, PANEL);
        g.enableScissor(x - 2, top, x + lw + 2, bottom);
        int y = top - listScroll;
        for (Catalog.Entry e : filtered) {
            if (y + ROW > top && y < bottom) {
                boolean sel = e == entry, hover = mx >= x && mx < x + lw && my >= y && my < y + ROW && my >= top && my < bottom;
                if (sel) g.fill(x - 2, y, x + lw + 2, y + ROW, 0x404FD1FF);
                else if (hover) g.fill(x - 2, y, x + lw + 2, y + ROW, HOVER);
                String tiers = e.members().size() > 1 ? " " + e.members().size() + "T" : "";
                int tw = font.width(tiers);
                g.drawString(font, font.plainSubstrByWidth(name(e).getString(), lw - tw - 2), x + 2, y + 3, sel ? ACCENT : TEXT, false);
                if (!tiers.isEmpty()) g.drawString(font, tiers, x + lw - tw, y + 3, DIM, false);
            }
            y += ROW;
        }
        g.disableScissor();
    }

    private void renderPreview(GuiGraphics g, int mx, int my) {
        int px = prevX(), py = prevY(), pw = prevW(), ph = prevH();
        g.fill(px, py, px + pw, py + ph, 0xF0090C10);
        g.renderOutline(px, py, pw, ph, BORDER);
        if (entry == null) return;
        g.drawString(font, font.plainSubstrByWidth(PlacerItem.displayName(currentId()).getString(), pw), px, y0 + PAD + 2, TEXT, false);
        String count = Component.translatable("mbplacer.gui.blocks", preview.size()).getString();
        g.drawString(font, count, px + pw - font.width(count) - 4, py + ph - 12, DIM, false);
        if (entry.members().size() > 1) {
            int ty = py + ph + 6;
            boolean hl = mx >= px && mx < px + 20 && my >= ty - 4 && my < ty + 12, hr = mx >= px + pw - 20 && mx < px + pw && my >= ty - 4 && my < ty + 12;
            g.drawString(font, "<", px + 6, ty, hl ? ACCENT : DIM, false);
            g.drawString(font, ">", px + pw - 12, ty, hr ? ACCENT : DIM, false);
            g.drawCenteredString(font, Component.translatable("mbplacer.gui.tier", tier + 1, entry.members().size()), px + pw / 2, ty, TEXT);
        }
        g.enableScissor(px + 1, py + 1, px + pw - 1, py + ph - 1);
        PoseStack ps = g.pose();
        ps.pushPose();
        float s = Math.min(pw, ph) / (size * 1.75f) * zoom;
        ps.translate(px + pw / 2f, py + ph / 2f, 400);
        ps.scale(s, -s, -s);
        ps.mulPose(Axis.XP.rotationDegrees(pitch));
        ps.mulPose(Axis.YP.rotationDegrees(yaw));
        ps.translate(-cx, -cy, -cz);
        Lighting.setupFor3DItems();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        var br = minecraft.getBlockRenderer();
        for (var e : preview) {
            ps.pushPose();
            BlockPos p = e.getKey();
            ps.translate(p.getX(), p.getY(), p.getZ());
            br.renderSingleBlock(e.getValue(), ps, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            ps.popPose();
        }
        buffers.endBatch();
        ps.popPose();
        g.disableScissor();
    }

    private void renderSide(GuiGraphics g, int mx, int my) {
        int x = sideX(), top = sideTop(), bottom = sideBottom();
        g.fill(x - 4, top - 2, x + SIDE_W + 2, bottom + 2, PANEL);
        g.enableScissor(x - 4, top, x + SIDE_W + 2, bottom);
        int y = top - sideScroll;
        for (Line l : lines) {
            if (y + l.height() > top && y < bottom) l.render(g, x, y, SIDE_W - 4, mx, my >= top && my < bottom ? my : -1000);
            y += l.height();
        }
        g.disableScissor();
    }

    private int sideContentHeight() {
        return lines.stream().mapToInt(Line::height).sum();
    }

    // ---------- input ----------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int x = x0 + PAD, lw = LIST_W - 2 * PAD;
        if (mx >= x && mx < x + lw && my >= listTop() && my < listBottom()) {
            int i = (int) ((my - listTop() + listScroll) / ROW);
            if (i >= 0 && i < filtered.size()) choose(filtered.get(i));
            return true;
        }
        if (entry != null && entry.members().size() > 1) {
            int px = prevX(), pw = prevW(), ty = prevY() + prevH() + 6;
            if (my >= ty - 4 && my < ty + 12) {
                if (mx >= px && mx < px + 20) { tier = Math.max(0, tier - 1); counts.clear(); rebuild(true); return true; }
                if (mx >= px + pw - 20 && mx < px + pw) { tier = Math.min(entry.members().size() - 1, tier + 1); counts.clear(); rebuild(true); return true; }
            }
        }
        if (mx >= sideX() && mx < sideX() + SIDE_W && my >= sideTop() && my < sideBottom()) {
            int y = sideTop() - sideScroll;
            for (Line l : List.copyOf(lines)) {
                if (my >= y && my < y + l.height()) return l.click(sideX(), y, SIDE_W - 4, mx, my, hasShiftDown());
                y += l.height();
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (mx >= prevX() && mx < prevX() + prevW() && my >= prevY() && my < prevY() + prevH()) {
            yaw += (float) dx * 1.5f;
            pitch = Math.max(-90, Math.min(90, pitch + (float) dy * 1.5f));
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < x0 + LIST_W) {
            int max = Math.max(0, filtered.size() * ROW - (listBottom() - listTop()));
            listScroll = (int) Math.max(0, Math.min(max, listScroll - delta * ROW * 2));
        } else if (mx >= sideX()) {
            int max = Math.max(0, sideContentHeight() - (sideBottom() - sideTop()));
            sideScroll = (int) Math.max(0, Math.min(max, sideScroll - delta * ROW * 2));
        } else {
            zoom = (float) Math.max(0.3, Math.min(6, zoom * (delta > 0 ? 1.15 : 1 / 1.15)));
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
