package dev.sylviromi.mbplacer.client;

import dev.sylviromi.mbplacer.build.Materials;
import dev.sylviromi.mbplacer.build.Placements;
import dev.sylviromi.mbplacer.build.Resolver;
import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.net.ClientData;
import dev.sylviromi.mbplacer.net.Net;
import dev.sylviromi.mbplacer.structure.*;
import dev.sylviromi.mbplacer.structure.gen.Generator;
import dev.sylviromi.mbplacer.structure.gen.Param;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import static dev.sylviromi.mbplacer.client.Ui.*;

/** Edits one multiblock: big 3D view on the left, Settings / Variants / Materials tabs on the right. Changes go to the tool live. */
public class EditorScreen extends Screen {
    private static final int ROW = 14, PAD = 6, BAR = 20, TAB = 16, CELL_SIZE = 20, GRID_TOP = 20;
    private enum Tab {SETTINGS, VARIANTS, MATERIALS}

    private final Catalog.Entry entry;
    private int tier;
    private int[] params;
    /** The player's choice per swappable position (blueprint coordinates). */
    private final Map<BlockPos, String> overrides = new LinkedHashMap<>();
    private Map<BlockPos, BlockSpec> base = Map.of();
    private Map<BlockPos, VariantSet> slotMap = Map.of();
    private List<Resolver.Check> checks = List.of();

    private int x0, y0, w, h, sideW;
    private Tab tab = Tab.SETTINGS;
    private List<Tab> tabs = List.of();
    private List<Line> lines = List.of();
    private int sideScroll;
    private String lastReport = "";

    private final Map<BlockPos, BlockState> preview = new LinkedHashMap<>();
    private Bounds bounds = Bounds.EMPTY;
    private StructureMesh mesh;
    private int layer; // 0 = all layers, else the number of layers shown from the bottom
    private final View view = new View();
    private boolean draggingSlider;
    /** Swappable block under the mouse in the 3D view; where a click started; the block being edited in the side panel. */
    private BlockPos hoveredSlot;
    private double pressX = -1, pressY;
    private BlockPos pressSlot;
    private BlockPos selectedSlot;

    /** Set when editing a placed multiblock: changes are previewed against it and applied in place. */
    private final Placements.Rec editing;

    EditorScreen(Catalog.Entry entry) {
        this(entry, null);
    }

    EditorScreen(Catalog.Entry entry, Placements.Rec editing) {
        super(PlacerItem.displayName(entry.key()));
        this.entry = entry;
        this.editing = editing;
        Selection sel = editing != null ? Selection.load(editing.selection()) : PlacerItem.selection(WorldGhost.heldTool());
        int i = entry.members().indexOf(sel.entry());
        if (i >= 0) {
            tier = i;
            params = sel.params();
            overrides.putAll(sel.overrides());
            // older tools stored counts: turn them into per-position choices
            if (!sel.counts().isEmpty()) {
                Map<BlockPos, BlockSpec> b = Resolver.specs(Catalog.client, new Selection(sel.entry(), sel.params(), Map.of()));
                if (b != null) overrides.putAll(Resolver.countsToOverrides(Resolver.slots(Catalog.client, sel.entry(), b), sel.counts()));
            }
        } else {
            tier = entry.members().size() - 1;
            params = generator().map(g -> g.params().stream().mapToInt(Param::def).toArray()).orElse(new int[0]);
        }
    }

    // ---------- state ----------

    private String currentId() {
        return entry.members().get(Math.min(tier, entry.members().size() - 1));
    }

    private Optional<Generator> generator() {
        return Catalog.client.generator(currentId());
    }

    private Selection selection() {
        return new Selection(currentId(), params, Map.of(), Map.copyOf(overrides));
    }

    void devCount(String set, String block, int n) {
        for (int i = 0; i < n; i++) VariantCounts.add(slotsOf(set), overrides, block);
        rebuild(true);
    }

    /** One set's positions, in order. */
    private List<BlockPos> slotsOf(String setId) {
        return slotMap.entrySet().stream().filter(e -> e.getValue().id().equals(setId)).map(Map.Entry::getKey).toList();
    }

    /** Dev: one energy-port slot gets an item port, so the structure check shows a mismatch. */
    void devWrongPort() {
        slotMap.entrySet().stream().filter(e -> e.getValue().id().contains("energy")).findFirst()
                .ifPresent(e -> overrides.put(e.getKey(), "mm:tiny_item_port_input"));
        rebuild(true);
    }

    /** Dev: selects the first slot for editing. */
    void devPickFirstSlot() {
        slotMap.keySet().stream().findFirst().ifPresent(this::select);
    }

    void devLayer(int l) {
        layer = l;
        closeMesh();
    }

    void showTab(String name) {
        Tab t = Tab.valueOf(name);
        if (tabs.contains(t)) tab = t;
        lines = buildLines();
    }

    @Override
    protected void init() {
        x0 = 8;
        y0 = 8;
        w = width - 16;
        h = height - 16;
        sideW = Math.max(160, Math.min(240, w * 34 / 100));
        rebuild(true);
    }

    /** Recomputes preview and side panel; sends the selection to the server. */
    private void rebuild(boolean send) {
        preview.clear();
        closeMesh();
        Map<BlockPos, BlockSpec> b = Resolver.specs(Catalog.client, new Selection(currentId(), params, Map.of()));
        base = b == null ? Map.of() : b;
        slotMap = Resolver.slots(Catalog.client, currentId(), base);
        overrides.keySet().retainAll(slotMap.keySet()); // e.g. after a size change
        Map<BlockPos, BlockSpec> specs = Resolver.specs(Catalog.client, selection());
        checks = specs == null ? List.of() : Resolver.check(Catalog.client, currentId(), slotMap, specs);
        if (specs != null) specs.forEach((p, spec) -> {
            BlockState s = Resolver.state(spec);
            if (s != null) preview.put(p, s);
        });
        bounds = Bounds.of(preview.keySet());
        if (layer > bounds.dy()) layer = 0;
        List<Tab> t = new ArrayList<>();
        if (generator().isPresent()) t.add(Tab.SETTINGS);
        if (!slotMap.isEmpty()) t.add(Tab.VARIANTS);
        t.add(Tab.MATERIALS);
        tabs = t;
        if (!tabs.contains(tab)) tab = tabs.get(0);
        lines = buildLines();
        if (send) Net.CHANNEL.sendToServer(editing != null ? new Net.UpdatePlaced(editing.id(), selection().save(), false) : new Net.SetSelection(selection().save()));
    }

    /** Sets in the structure with their slot counts, in first-appearance order. */
    private Map<VariantSet, Integer> slots() {
        Map<VariantSet, Integer> out = new LinkedHashMap<>();
        slotMap.values().forEach(set -> out.merge(set, 1, Integer::sum));
        return out;
    }

    private int problems() {
        return (int) checks.stream().filter(c -> !c.ok()).count();
    }

    // ---------- side panel lines ----------

    private interface Line {
        default int height() { return ROW; }

        void render(GuiGraphics g, int x, int y, int w, int mx, int my);

        default boolean click(int x, int y, int w, double mx, double my, int button) { return false; }
    }

    private Line header(Component text, String right) {
        return new Line() {
            @Override public int height() { return ROW + 4; }

            @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                g.drawString(font, font.plainSubstrByWidth(text.getString(), w - font.width(right) - 4), x, y + 6, ACCENT, false);
                g.drawString(font, right, x + w - font.width(right), y + 6, DIM, false);
                g.fill(x, y + 16, x + w, y + 17, BORDER);
            }
        };
    }

    private Line text(Component text, int color) {
        return (g, x, y, w, mx, my) -> g.drawString(font, font.plainSubstrByWidth(text.getString(), w), x, y + 3, color, false);
    }

    /** "label   < value >": click the arrows, shift steps by 10. */
    private Line stepper(Component label, Supplier<String> value, IntConsumer step) {
        return new Line() {
            @Override public int height() { return ROW + 2; }

            @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                String v = value.get();
                int vw = Math.max(24, font.width(v) + 4), rx = x + w;
                if (in(mx, my, x - 3, y, w + 6, height())) g.fill(x - 3, y, x + w + 3, y + height(), HOVER);
                String shown = font.plainSubstrByWidth(label.getString(), w - vw - 34);
                g.drawString(font, shown, x, y + 4, TEXT, false);
                if (shown.length() < label.getString().length() && in(mx, my, x, y, w - vw - 34, height())) setTooltipForNextRenderPass(label);
                button(g, rx - vw - 28, y + 1, 13, 13, Icon.LEFT, false, mx, my);
                g.drawCenteredString(font, v, rx - 14 - vw / 2, y + 4, TEXT);
                button(g, rx - 13, y + 1, 13, 13, Icon.RIGHT, false, mx, my);
            }

            @Override public boolean click(int x, int y, int w, double mx, double my, int button) {
                int vw = Math.max(24, font.width(value.get()) + 4), rx = x + w, d = hasShiftDown() ? 10 : 1;
                if (in(mx, my, rx - vw - 28, y + 1, 13, 13)) step.accept(-d);
                else if (in(mx, my, rx - 13, y + 1, 13, 13)) step.accept(d);
                else return false;
                return true;
            }
        };
    }

    private static ItemStack iconOf(String blockId) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        var block = rl == null ? null : ForgeRegistries.BLOCKS.getValue(rl);
        return block == null ? ItemStack.EMPTY : new ItemStack(block);
    }

    /** Icon grid of one variant set: count badges; click +1, right-click -1, shift-click fill, shift-right-click clear. */
    private Line grid(VariantSet set) {
        List<BlockPos> setSlots = slotsOf(set.id());
        List<String> blocks = set.blocks();
        return new Line() {
            int cols(int w) {
                int fit = Math.max(1, w / CELL_SIZE);
                return set.columns() > 0 ? Math.min(set.columns(), fit) : fit;
            }

            @Override public int height() {
                return GRID_TOP + ((blocks.size() + cols(sideW - 12) - 1) / cols(sideW - 12)) * CELL_SIZE + 4;
            }

            @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                int used = VariantCounts.used(setSlots, overrides), slots = setSlots.size();
                g.fill(x, y + 2, x + w, y + 6, CELL);
                g.fill(x, y + 2, x + w * used / Math.max(1, slots), y + 6, GREEN);
                g.drawString(font, Component.translatable("mbplacer.gui.unchanged", slots - used), x, y + 9, DIM, false);
                int cols = cols(w), gy = y + GRID_TOP;
                for (int i = 0; i < blocks.size(); i++) {
                    int cx = x + (i % cols) * CELL_SIZE, cy = gy + (i / cols) * CELL_SIZE;
                    int n = VariantCounts.count(setSlots, overrides, blocks.get(i));
                    boolean hover = in(mx, my, cx, cy, CELL_SIZE - 2, CELL_SIZE - 2);
                    g.fill(cx, cy, cx + CELL_SIZE - 2, cy + CELL_SIZE - 2, hover ? BTN_HOVER : CELL);
                    g.renderOutline(cx, cy, CELL_SIZE - 2, CELL_SIZE - 2, n > 0 || hover ? ACCENT : BORDER);
                    ItemStack icon = iconOf(blocks.get(i));
                    g.renderItem(icon, cx + 1, cy + 1);
                    if (n > 0) badge(g, cx, cy, n);
                    if (hover) setTooltipForNextRenderPass(Component.literal((icon.isEmpty() ? blocks.get(i) : icon.getHoverName().getString())
                            + "\n" + Component.translatable("mbplacer.gui.grid_tip", n).getString()));
                }
            }

            @Override public boolean click(int x, int y, int w, double mx, double my, int button) {
                int cols = cols(w), i = (int) ((mx - x) / CELL_SIZE) + (int) ((my - y - GRID_TOP) / CELL_SIZE) * cols;
                if (my < y + GRID_TOP || mx >= x + cols * CELL_SIZE || i < 0 || i >= blocks.size()) return false;
                String b = blocks.get(i);
                if (button == 1 && hasShiftDown()) VariantCounts.clear(setSlots, overrides, b);
                else if (button == 1) VariantCounts.remove(setSlots, overrides, b);
                else if (hasShiftDown()) VariantCounts.fill(setSlots, overrides, b);
                else VariantCounts.add(setSlots, overrides, b);
                rebuild(true);
                return true;
            }
        };
    }

    private void badge(GuiGraphics g, int cx, int cy, int n) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        String s = String.valueOf(n);
        g.fill(cx + CELL_SIZE - 3 - font.width(s) - 1, cy + CELL_SIZE - 11, cx + CELL_SIZE - 2, cy + CELL_SIZE - 2, 0xE0000000);
        g.drawString(font, s, cx + CELL_SIZE - 2 - font.width(s), cy + CELL_SIZE - 10, YELLOW, false);
        g.pose().popPose();
    }

    private Line actionLine(Component label, Runnable action) {
        return new Line() {
            @Override public int height() { return 22; }

            @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                Ui.button(g, font, x, y + 3, w, 16, label, false, mx, my);
            }

            @Override public boolean click(int x, int y, int w, double mx, double my, int button) {
                action.run();
                return true;
            }
        };
    }

    /**
     * A variant set's name from its blocks' own names: the words they all end with ("Energy Port Input", "Mechanical Pipe"),
     * else the words they all start with ("Fission Reactor"). A pack can still name it with mbplacer.variants.<set>.
     */
    private static Component setName(VariantSet set) {
        String key = "mbplacer.variants." + Catalog.shortName(set.id());
        if (net.minecraft.locale.Language.getInstance().has(key)) return Component.translatable(key);
        List<List<String>> names = set.blocks().stream().map(b -> List.of(iconOf(b).getHoverName().getString().split(" "))).toList();
        List<String> shared = reversed(Catalog.sharedWords(names.stream().map(EditorScreen::reversed).toList()));
        if (shared.isEmpty()) shared = Catalog.sharedWords(names);
        return Component.literal(shared.isEmpty() ? Catalog.prettify(Catalog.shortName(set.id())) : String.join(" ", shared));
    }

    private static List<String> reversed(List<String> l) {
        List<String> out = new ArrayList<>(l);
        Collections.reverse(out);
        return out;
    }

    private int missing() {
        if (!ClientData.reportFor.equals(currentId())) return 0;
        return (int) ClientData.rows.stream().filter(r -> r.missing() > 0).count();
    }

    private List<Line> buildLines() {
        List<Line> out = new ArrayList<>();
        switch (tab) {
            case SETTINGS -> generator().ifPresent(gen -> {
                List<Param> ps = gen.params();
                params = Resolver.clampedParams(gen, params);
                for (int i = 0; i < ps.size(); i++) {
                    int idx = i;
                    Param p = ps.get(i);
                    out.add(stepper(p.label(), () -> p.valueLabel(params[idx]).getString(),
                            d -> { params[idx] = p.clamp(params[idx] + d); rebuild(true); }));
                }
            });
            case VARIANTS -> {
                if (selectedSlot != null && slotMap.containsKey(selectedSlot)) selectedLines(out);
                // MM-style families: the structure needs as many of each kind as the blueprint has
                if (!checks.isEmpty()) {
                    out.add(header(Component.translatable(problems() == 0 ? "mbplacer.gui.check_ok" : "mbplacer.gui.check_bad"), ""));
                    for (Resolver.Check c : checks)
                        out.add(text(Component.literal(setName(c.set()).getString() + "  " + c.actual() + " / " + c.required()), c.ok() ? GREEN : RED));
                }
                out.add(text(Component.translatable("mbplacer.gui.pick_hint"), DIM));
                slots().forEach((set, n) -> {
                    out.add(header(setName(set), VariantCounts.used(slotsOf(set.id()), overrides) + " / " + n));
                    out.add(grid(set));
                });
            }
            case MATERIALS -> {
                out.add(text(Component.translatable("mbplacer.gui.me." + ClientData.me.name()), ClientData.me == Materials.MeStatus.CONNECTED ? GREEN : DIM));
                if (!ClientData.reportFor.equals(currentId())) {
                    out.add(text(Component.literal("..."), DIM));
                    break;
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
                            if (in(mx, my, x - 3, y, w + 6, 18)) g.fill(x - 3, y, x + w + 3, y + 18, HOVER);
                            g.renderItem(stack, x, y + 1);
                            String count = Math.min(have, r.needed()) + "/" + r.needed();
                            g.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), w - 24 - font.width(count)), x + 18, y + 5, TEXT, false);
                            g.drawString(font, count, x + w - font.width(count), y + 5, color, false);
                            if (in(mx, my, x, y, w, 18))
                                setTooltipForNextRenderPass(Component.literal(stack.getHoverName().getString() + "\nInventory: " + r.inventory()
                                        + (r.me() >= 0 ? "\nME: " + r.me() : "") + (r.craftable() ? "\nCraftable" : "")));
                        }
                    });
                }
                if (craftable && ClientData.me == Materials.MeStatus.CONNECTED)
                    out.add(actionLine(Component.translatable("mbplacer.gui.request_crafts"), () -> Net.CHANNEL.sendToServer(new Net.RequestCrafts())));
            }
        }
        return out;
    }

    // ---------- geometry ----------

    private int bodyTop() { return y0 + BAR + 4; }
    /** Apply sits left of the tier selector (or at the right edge without tiers). */
    private int applyX() {
        if (entry.members().size() <= 1) return x0 + w - PAD - 50;
        String t = Component.translatable("mbplacer.gui.tier", tier + 1, entry.members().size()).getString();
        return x0 + w - PAD - 16 - font.width(t) - 20 - 58;
    }
    private int bodyBottom() { return y0 + h - PAD; }
    private int sideX() { return x0 + w - sideW - PAD; }
    private int viewX() { return x0 + PAD; }
    private int viewW() { return sideX() - PAD - viewX(); }
    private int viewH() { return bodyBottom() - bodyTop(); }
    private int contentTop() { return bodyTop() + TAB + 6; }
    private int sliderX() { return viewX() + viewW() - 14; }
    private int sliderTop() { return bodyTop() + 24; }
    private int sliderBottom() { return bodyBottom() - 24; }

    // ---------- rendering ----------

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        String rk = ClientData.reportFor + ClientData.rows.hashCode() + ClientData.me;
        if (!rk.equals(lastReport)) {
            lastReport = rk;
            lines = buildLines();
        }
        renderBackground(g);
        g.fill(x0, y0, x0 + w, y0 + h, BG);
        g.renderOutline(x0, y0, w, h, BORDER);
        renderTopBar(g, mx, my);
        renderView(g, mx, my);
        renderSide(g, mx, my);
        super.render(g, mx, my, partial);
    }

    private void renderTopBar(GuiGraphics g, int mx, int my) {
        int y = y0 + 4;
        button(g, font, x0 + PAD, y, 44, 15, Component.translatable(editing != null ? "gui.cancel" : "gui.back"), false, mx, my);
        Component name = PlacerItem.displayName(currentId());
        if (editing != null) name = Component.translatable("mbplacer.gui.editing", name);
        g.drawString(font, font.plainSubstrByWidth(name.getString(), w / 2), x0 + PAD + 52, y + 4, editing != null ? YELLOW : TEXT, false);
        if (editing != null && button(g, font, applyX(), y, 50, 15, Component.translatable("mbplacer.gui.apply"), true, mx, my))
            setTooltipForNextRenderPass(Component.translatable("mbplacer.gui.apply_tip"));
        if (entry.members().size() > 1) {
            String t = Component.translatable("mbplacer.gui.tier", tier + 1, entry.members().size()).getString();
            int tx = x0 + w - PAD - 16 - font.width(t) - 20;
            button(g, tx, y, 15, 15, Icon.LEFT, false, mx, my);
            g.drawString(font, t, tx + 26, y + 4, ACCENT, false);
            button(g, x0 + w - PAD - 16, y, 15, 15, Icon.RIGHT, false, mx, my);
        }
    }

    private Map<BlockPos, BlockState> shown() {
        if (layer == 0) return preview;
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        preview.forEach((p, s) -> { if (p.getY() < bounds.minY() + layer) out.put(p, s); });
        return out;
    }

    private void renderView(GuiGraphics g, int mx, int my) {
        int vx = viewX(), vy = bodyTop(), vw = viewW(), vh = viewH();
        g.fill(vx, vy, vx + vw, vy + vh, VIEW);
        g.renderOutline(vx, vy, vw, vh, BORDER);
        g.enableScissor(vx + 1, vy + 1, vx + vw - 1, vy + vh - 1);
        if (mesh == null && !preview.isEmpty()) mesh = new StructureMesh(shown());
        hoveredSlot = null;
        if (!draggingSlider && in(mx, my, vx, vy, vw - 20, vh)) {
            BlockPos p = view.pick(mx, my, shown().keySet());
            if (p != null && slotMap.containsKey(p)) hoveredSlot = p;
        }
        if (mesh != null) view.draw(g, mesh, vx, vy, vw - 20, vh, bounds, hoveredSlot, selectedSlot);
        if (hoveredSlot != null) setTooltipForNextRenderPass(Component.literal(iconOf(blockAt(hoveredSlot)).getHoverName().getString()
                + "\n" + Component.translatable("mbplacer.gui.pick_tip").getString()));
        g.disableScissor();
        // toolbar
        int bx = vx + 4, by = vy + 4;
        if (button(g, bx, by, 15, 15, Icon.CCW, false, mx, my)) setTooltipForNextRenderPass(Component.translatable("mbplacer.gui.turn_left"));
        if (button(g, bx + 18, by, 15, 15, Icon.CW, false, mx, my)) setTooltipForNextRenderPass(Component.translatable("mbplacer.gui.turn_right"));
        button(g, font, bx + 36, by, 38, 15, Component.translatable("controls.reset"), false, mx, my);
        button(g, font, bx + 76, by, 34, 15, Component.translatable("mbplacer.gui.spin"), view.spin, mx, my);
        String count = Component.translatable("mbplacer.gui.blocks", preview.size()).getString();
        g.drawString(font, count, vx + vw - 20 - font.width(count), vy + 8, DIM, false);
        g.drawString(font, Component.translatable("mbplacer.gui.view_hint"), vx + 4, vy + vh - 12, DIM, false);
        // layer slider: top = all layers
        int sx = sliderX(), st = sliderTop(), sb = sliderBottom(), layers = (int) bounds.dy();
        g.fill(sx + 3, st, sx + 7, sb, CELL);
        int shownLayers = layer == 0 ? layers : layer;
        int ky = sb - (sb - st) * shownLayers / Math.max(1, layers);
        g.fill(sx, ky - 3, sx + 10, ky + 3, draggingSlider || in(mx, my, sx - 2, st - 4, 14, sb - st + 8) ? ACCENT : BTN_ON);
        String lt = layer == 0 ? Component.translatable("mbplacer.gui.layers_all").getString() : layer + "/" + layers;
        g.drawCenteredString(font, lt, sx + 5, sb + 6, DIM); // under the slider: the block count sits top right
    }

    /** The block a slot has now: the player's choice, else the blueprint's. */
    private String blockAt(BlockPos pos) {
        return overrides.getOrDefault(pos, base.get(pos).id());
    }

    /** Edits this slot in the side panel (Variants tab), keeping the view clear. */
    private void select(BlockPos pos) {
        selectedSlot = pos;
        if (tabs.contains(Tab.VARIANTS)) tab = Tab.VARIANTS;
        sideScroll = 0;
        lines = buildLines();
    }

    /** The previous or next swappable block, in position order, wrapping around. */
    private void step(int d) {
        List<BlockPos> all = List.copyOf(slotMap.keySet());
        int i = all.indexOf(selectedSlot);
        if (!all.isEmpty()) select(all.get(Math.floorMod(i + d, all.size())));
    }

    private void choose(String block) {
        if (block.equals(base.get(selectedSlot).id())) overrides.remove(selectedSlot);
        else overrides.put(selectedSlot, block);
        rebuild(true);
    }

    /** "Selected block": its name with prev/next/close, then one labelled row of choices per set of its family. */
    private void selectedLines(List<Line> out) {
        BlockPos pos = selectedSlot;
        String now = blockAt(pos), original = base.get(pos).id();
        out.add(new Line() {
            @Override public int height() { return 22; }

            @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                g.fill(x - 3, y, x + w + 3, y + height(), 0x334FD1FF);
                g.renderItem(iconOf(now), x, y + 3);
                g.drawString(font, font.plainSubstrByWidth(iconOf(now).getHoverName().getString(), w - 18 - 51), x + 19, y + 7, TEXT, false);
                if (Ui.button(g, x + w - 49, y + 4, 15, 15, Icon.LEFT, false, mx, my)) setTooltipForNextRenderPass(Component.translatable("mbplacer.gui.slot_prev"));
                if (Ui.button(g, x + w - 32, y + 4, 15, 15, Icon.RIGHT, false, mx, my)) setTooltipForNextRenderPass(Component.translatable("mbplacer.gui.slot_next"));
                if (Ui.button(g, font, x + w - 15, y + 4, 15, 15, Component.literal("x"), false, mx, my)) setTooltipForNextRenderPass(Component.translatable("gui.done"));
            }

            @Override public boolean click(int x, int y, int w, double mx, double my, int button) {
                if (in(mx, my, x + w - 49, y + 4, 15, 15)) step(-1);
                else if (in(mx, my, x + w - 32, y + 4, 15, 15)) step(1);
                else if (in(mx, my, x + w - 15, y + 4, 15, 15)) { selectedSlot = null; lines = buildLines(); }
                else return false;
                return true;
            }
        });
        for (VariantSet set : Catalog.client.family(slotMap.get(pos), currentId())) {
            out.add(text(setName(set), DIM));
            List<String> blocks = set.blocks();
            out.add(new Line() {
                int cols(int w) { return Math.max(1, w / CELL_SIZE); }

                @Override public int height() { return ((blocks.size() + cols(sideW - 12) - 1) / cols(sideW - 12)) * CELL_SIZE + 2; }

                @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                    for (int i = 0; i < blocks.size(); i++) {
                        String b = blocks.get(i);
                        int cx = x + (i % cols(w)) * CELL_SIZE, cy = y + (i / cols(w)) * CELL_SIZE;
                        boolean hover = in(mx, my, cx, cy, CELL_SIZE - 2, CELL_SIZE - 2), on = b.equals(now);
                        g.fill(cx, cy, cx + CELL_SIZE - 2, cy + CELL_SIZE - 2, on ? BTN_ON : hover ? BTN_HOVER : CELL);
                        g.renderOutline(cx, cy, CELL_SIZE - 2, CELL_SIZE - 2, on || hover ? ACCENT : b.equals(original) ? DIM : BORDER);
                        ItemStack icon = iconOf(b);
                        g.renderItem(icon, cx + 1, cy + 1);
                        if (hover) setTooltipForNextRenderPass(Component.literal(icon.getHoverName().getString()
                                + (b.equals(original) ? "\n" + Component.translatable("mbplacer.gui.pick_default").getString() : "")));
                    }
                }

                @Override public boolean click(int x, int y, int w, double mx, double my, int button) {
                    int c = (int) ((mx - x) / CELL_SIZE), i = c + (int) ((my - y) / CELL_SIZE) * cols(w);
                    if (c >= cols(w) || i < 0 || i >= blocks.size()) return false;
                    choose(blocks.get(i));
                    return true;
                }
            });
        }
    }

    private void renderSide(GuiGraphics g, int mx, int my) {
        int x = sideX(), top = bodyTop();
        g.fill(x, top, x + sideW, bodyBottom(), PANEL);
        g.renderOutline(x, top, sideW, bodyBottom() - top, BORDER);
        int tw = (sideW - 8) / tabs.size();
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            Component label = Component.translatable("mbplacer.gui.tab." + t.name().toLowerCase(Locale.ROOT));
            int m = t == Tab.MATERIALS ? missing() : t == Tab.VARIANTS ? problems() : 0;
            if (m > 0) label = label.copy().append(Component.literal(" " + m).withStyle(s -> s.withColor(RED)));
            button(g, font, x + 4 + i * tw, top + 4, tw - 2, TAB, label, t == tab, mx, my);
        }
        int ct = contentTop(), bottom = bodyBottom() - 4, cx = x + 6, cw = sideW - 12;
        g.enableScissor(x + 1, ct, x + sideW - 1, bottom);
        int y = ct - sideScroll;
        for (Line l : lines) {
            if (y + l.height() > ct && y < bottom) l.render(g, cx, y, cw, mx, my >= ct && my < bottom ? my : -1000);
            y += l.height();
        }
        g.disableScissor();
    }

    // ---------- input ----------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int y = y0 + 4;
        if (in(mx, my, x0 + PAD, y, 44, 15)) {
            minecraft.setScreen(editing != null ? null : new BrowserScreen());
            return true;
        }
        if (editing != null && in(mx, my, applyX(), y, 50, 15)) {
            Net.CHANNEL.sendToServer(new Net.UpdatePlaced(editing.id(), selection().save(), true));
            onClose();
            return true;
        }
        if (entry.members().size() > 1) {
            String t = Component.translatable("mbplacer.gui.tier", tier + 1, entry.members().size()).getString();
            int tx = x0 + w - PAD - 16 - font.width(t) - 20;
            int next = in(mx, my, tx, y, 16, 16) ? tier - 1 : in(mx, my, x0 + w - PAD - 16, y, 16, 16) ? tier + 1 : tier;
            if (next != tier) {
                tier = Math.max(0, Math.min(entry.members().size() - 1, next));
                overrides.clear();
                rebuild(true);
                return true;
            }
        }
        int bx = viewX() + 4, by = bodyTop() + 4;
        if (in(mx, my, bx, by, 15, 15)) { view.yaw -= 45; return true; }
        if (in(mx, my, bx + 18, by, 15, 15)) { view.yaw += 45; return true; }
        if (in(mx, my, bx + 36, by, 38, 15)) { view.reset(); return true; }
        if (in(mx, my, bx + 76, by, 34, 15)) { view.spin = !view.spin; return true; }
        if (in(mx, my, sliderX() - 2, sliderTop() - 4, 14, sliderBottom() - sliderTop() + 8)) {
            draggingSlider = true;
            setLayer(my);
            return true;
        }
        if (button == 0 && hoveredSlot != null) {
            // a click (not a drag) on a swappable block selects it, see mouseReleased
            pressX = mx;
            pressY = my;
            pressSlot = hoveredSlot;
            return true;
        }
        int x = sideX(), tw = (sideW - 8) / tabs.size();
        for (int i = 0; i < tabs.size(); i++)
            if (in(mx, my, x + 4 + i * tw, bodyTop() + 4, tw - 2, TAB)) {
                tab = tabs.get(i);
                sideScroll = 0;
                lines = buildLines();
                return true;
            }
        if (in(mx, my, x, contentTop(), sideW, bodyBottom() - contentTop())) {
            int ly = contentTop() - sideScroll;
            for (Line l : List.copyOf(lines)) {
                if (my >= ly && my < ly + l.height()) return l.click(x + 6, ly, sideW - 12, mx, my, button);
                ly += l.height();
            }
        }
        return false;
    }

    private void setLayer(double my) {
        int layers = (int) bounds.dy();
        double f = (sliderBottom() - my) / (sliderBottom() - sliderTop());
        int l = (int) Math.round(f * layers);
        int next = l >= layers ? 0 : Math.max(1, l);
        if (next != layer) {
            layer = next;
            closeMesh();
        }
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingSlider = false;
        if (pressSlot != null && Math.abs(mx - pressX) + Math.abs(my - pressY) < 4) select(pressSlot);
        pressSlot = null;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingSlider) {
            setLayer(my);
            return true;
        }
        if (in(mx, my, viewX(), bodyTop(), viewW(), viewH())) {
            if (button == 1) {
                view.panX += (float) dx;
                view.panY += (float) dy;
            } else view.rotate(dx, dy);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= sideX()) {
            int max = Math.max(0, lines.stream().mapToInt(Line::height).sum() - (bodyBottom() - 4 - contentTop()));
            sideScroll = (int) Math.max(0, Math.min(max, sideScroll - delta * ROW * 2));
        } else view.zoom(delta);
        return true;
    }

    private void closeMesh() {
        if (mesh != null) mesh.close();
        mesh = null;
    }

    @Override
    public void removed() {
        closeMesh();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (selectedSlot != null && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            selectedSlot = null;
            lines = buildLines();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
