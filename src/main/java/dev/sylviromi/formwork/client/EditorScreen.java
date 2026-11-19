package dev.sylviromi.formwork.client;

import dev.sylviromi.formwork.build.Materials;
import dev.sylviromi.formwork.build.Placements;
import dev.sylviromi.formwork.build.Resolver;
import dev.sylviromi.formwork.item.PlacerItem;
import dev.sylviromi.formwork.net.ClientData;
import dev.sylviromi.formwork.net.Net;
import dev.sylviromi.formwork.structure.*;
import dev.sylviromi.formwork.structure.gen.Generator;
import dev.sylviromi.formwork.structure.gen.Param;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import static dev.sylviromi.formwork.client.Ui.*;

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
            tier = 0; // a new pick starts at the first tier
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

    /** On/off switch row; the whole row toggles. */
    private Line toggle(Component label, java.util.function.BooleanSupplier on, Runnable flip) {
        return new Line() {
            @Override public int height() { return ROW + 2; }

            @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                boolean hover = in(mx, my, x - 3, y, w + 6, height()), v = on.getAsBoolean();
                if (hover) g.fill(x - 3, y, x + w + 3, y + height(), HOVER);
                g.drawString(font, font.plainSubstrByWidth(label.getString(), w - 34), x, y + 4, TEXT, false);
                int sx = x + w - 26, sy = y + 3;
                g.fill(sx, sy, sx + 26, sy + 10, v ? 0xFF2B8A5A : CELL);
                g.renderOutline(sx, sy, 26, 10, v ? GREEN : hover ? ACCENT : BORDER);
                int knob = v ? sx + 15 : sx + 1;
                g.fill(knob, sy + 1, knob + 10, sy + 9, v ? 0xFFFFFFFF : DIM);
            }

            @Override public boolean click(int x, int y, int w, double mx, double my, int button) {
                flip.run();
                return true;
            }
        };
    }

    /** One set: its name and how many of its blocks are changed; click to start editing its first block. */
    private Line setRow(VariantSet set, int n) {
        return new Line() {
            @Override public int height() { return 20; }

            @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                boolean hover = in(mx, my, x - 3, y, w + 6, height());
                g.fill(x - 3, y + 1, x + w + 3, y + height() - 1, hover ? BTN_HOVER : CELL);
                List<BlockPos> setSlots = slotsOf(set.id());
                g.renderItem(iconOf(setSlots.isEmpty() ? set.blocks().get(0) : blockAt(setSlots.get(0))), x, y + 2);
                String count = Component.translatable("formwork.gui.changed", VariantCounts.used(setSlots, overrides), n).getString();
                g.drawString(font, font.plainSubstrByWidth(setName(set).getString(), w - 22 - font.width(count)), x + 19, y + 6, TEXT, false);
                g.drawString(font, count, x + w - font.width(count), y + 6, DIM, false);
            }

            @Override public boolean click(int x, int y, int w, double mx, double my, int button) {
                slotsOf(set.id()).stream().findFirst().ifPresent(EditorScreen.this::select);
                return true;
            }
        };
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

    /** "40% ~1m 05s" for a row being crafted by AE2. */
    private static String craftLabel(Materials.Row r) {
        return r.crafting() + "%" + (r.eta() < 0 ? "" : r.eta() >= 60 ? " ~" + r.eta() / 60 + "m " + String.format("%02d", r.eta() % 60) + "s" : " ~" + r.eta() + "s");
    }

    /** Kinds of material the edit still lacks; 0 in creative (the server charges nothing there) or before the report arrives. */
    private long missingKinds() {
        if (minecraft.player == null || minecraft.player.isCreative() || !ClientData.reportFor.equals(currentId())) return 0;
        return ClientData.rows.stream().filter(r -> r.missing() > 0).count();
    }

    private static List<String> reversed(List<String> l) {
        List<String> out = new ArrayList<>(l);
        Collections.reverse(out);
        return out;
    }

    /**
     * A variant set's name from its blocks' own names: the words they all end with ("Energy Port Input", "Mechanical Pipe"),
     * else the words they all start with ("Fission Reactor"). A pack can still name it with formwork.variants.<set>.
     */
    private static Component setName(VariantSet set) {
        String key = "formwork.variants." + Catalog.shortName(set.id());
        if (net.minecraft.locale.Language.getInstance().has(key)) return Component.translatable(key);
        List<List<String>> names = set.blocks().stream().map(b -> List.of(iconOf(b).getHoverName().getString().split(" "))).toList();
        List<String> shared = reversed(Catalog.sharedWords(names.stream().map(EditorScreen::reversed).toList()));
        if (shared.isEmpty()) shared = Catalog.sharedWords(names);
        return Component.literal(shared.isEmpty() ? Catalog.prettify(Catalog.shortName(set.id())) : String.join(" ", shared));
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
                    if (p.labels().equals(Param.toggle("", false).labels())) out.add(toggle(p.label(), () -> params[idx] == 1, () -> { params[idx] = 1 - params[idx]; rebuild(true); }));
                    else out.add(stepper(p.label(), () -> p.valueLabel(params[idx]).getString(),
                            d -> { params[idx] = p.clamp(params[idx] + d); rebuild(true); }));
                }
            });
            case VARIANTS -> {
                if (selectedSlot != null && slotMap.containsKey(selectedSlot)) {
                    selectedLines(out);
                    break;
                }
                out.add(text(Component.translatable("formwork.gui.pick_hint"), DIM));
                slots().forEach((set, n) -> out.add(setRow(set, n)));
            }
            case MATERIALS -> {
                out.add(text(Component.translatable("formwork.gui.me." + ClientData.me.name()), ClientData.me == Materials.MeStatus.CONNECTED ? GREEN : DIM));
                if (!ClientData.reportFor.equals(currentId())) {
                    out.add(text(Component.literal("..."), DIM));
                    break;
                }
                boolean me = ClientData.me == Materials.MeStatus.CONNECTED;
                Map<Item, Integer> craftAll = new LinkedHashMap<>();
                if (me) ClientData.rows.stream().filter(r -> r.missing() > 0 && r.craftable() && r.crafting() < 0).forEach(r -> craftAll.put(r.item(), r.missing()));
                if (!craftAll.isEmpty())
                    out.add(actionLine(Component.translatable("formwork.gui.craft_all", craftAll.size()), () -> {
                        ClientHooks.resume = this;
                        Net.CHANNEL.sendToServer(new Net.CraftAll(craftAll));
                    }));
                for (Materials.Row r : ClientData.rows) {
                    int have = (int) Math.min(Integer.MAX_VALUE, r.inventory() + Math.max(0, r.me()));
                    int color = r.missing() == 0 ? GREEN : r.craftable() ? YELLOW : RED;
                    boolean canCraft = me && r.missing() > 0 && r.craftable();
                    ItemStack stack = new ItemStack(r.item());
                    out.add(new Line() {
                        @Override public int height() { return 18; }

                        @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                            if (in(mx, my, x - 3, y, w + 6, 18)) g.fill(x - 3, y, x + w + 3, y + 18, HOVER);
                            g.renderItem(stack, x, y + 1);
                            String count = (r.crafting() >= 0 ? craftLabel(r) + "  " : "") + Math.min(have, r.needed()) + "/" + r.needed();
                            g.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), w - 24 - font.width(count)), x + 18, y + 5, TEXT, false);
                            g.drawString(font, count, x + w - font.width(count), y + 5, color, false);
                            if (in(mx, my, x, y, w, 18))
                                setTooltipForNextRenderPass(Component.literal(stack.getHoverName().getString() + "\nInventory: " + r.inventory()
                                        + (r.me() >= 0 ? "\nME: " + r.me() : "")
                                        + (canCraft ? "\n" + Component.translatable("formwork.gui.click_craft").getString() : "")));
                        }

                        /** A missing, craftable row opens AE2's own craft screen for it. */
                        @Override public boolean click(int x, int y, int w, double mx, double my, int button) {
                            if (canCraft) {
                                ClientHooks.resume = EditorScreen.this;
                                Net.CHANNEL.sendToServer(new Net.OpenCraft(r.item(), r.missing()));
                            }
                            return canCraft;
                        }
                    });
                }
            }
        }
        return out;
    }

    // ---------- geometry ----------

    private int bodyTop() { return y0 + BAR + 4; }
    /** Tier selector in the top bar: {left arrow x, text x, right arrow x}, laid out from the right edge. */
    private int[] tierBox() {
        int right = x0 + w - PAD - 15, textX = right - 6 - font.width(tierText());
        return new int[]{textX - 6 - 15, textX, right};
    }

    private String tierText() {
        return Component.translatable("formwork.gui.tier", tier + 1, entry.members().size()).getString();
    }

    /** Apply sits left of the tier selector (or at the right edge without tiers). */
    private int applyX() {
        return entry.members().size() <= 1 ? x0 + w - PAD - 50 : tierBox()[0] - 8 - 50;
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
        if (editing != null) name = Component.translatable("formwork.gui.editing", name);
        g.drawString(font, font.plainSubstrByWidth(name.getString(), w / 2), x0 + PAD + 52, y + 4, editing != null ? YELLOW : TEXT, false);
        if (editing != null) {
            long missing = missingKinds();
            boolean hover = missing > 0 ? Ui.frame(g, applyX(), y, 50, 15, false, mx, my) : button(g, font, applyX(), y, 50, 15, Component.translatable("formwork.gui.apply"), true, mx, my);
            if (missing > 0) g.drawCenteredString(font, Component.translatable("formwork.gui.apply"), applyX() + 25, y + 4, DIM);
            if (hover) setTooltipForNextRenderPass(Component.translatable(missing > 0 ? "formwork.gui.apply_missing" : "formwork.gui.apply_tip", missing));
        }
        if (entry.members().size() > 1) {
            int[] t = tierBox();
            button(g, t[0], y, 15, 15, Icon.LEFT, false, mx, my);
            g.drawString(font, tierText(), t[1], y + 4, ACCENT, false);
            button(g, t[2], y, 15, 15, Icon.RIGHT, false, mx, my);
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
                + "\n" + Component.translatable("formwork.gui.pick_tip").getString()));
        g.disableScissor();
        // toolbar
        int bx = vx + 4, by = vy + 4;
        if (button(g, bx, by, 15, 15, Icon.CCW, false, mx, my)) setTooltipForNextRenderPass(Component.translatable("formwork.gui.turn_left"));
        if (button(g, bx + 18, by, 15, 15, Icon.CW, false, mx, my)) setTooltipForNextRenderPass(Component.translatable("formwork.gui.turn_right"));
        button(g, font, bx + 36, by, 38, 15, Component.translatable("controls.reset"), false, mx, my);
        button(g, font, bx + 76, by, 34, 15, Component.translatable("formwork.gui.spin"), view.spin, mx, my);
        String count = Component.translatable("formwork.gui.blocks", preview.size()).getString();
        g.drawString(font, count, vx + vw - 20 - font.width(count), vy + 8, DIM, false);
        g.drawString(font, Component.translatable("formwork.gui.view_hint"), vx + 4, vy + vh - 12, DIM, false);
        // MM-style families: whether the machine will form, always in sight
        if (!checks.isEmpty()) {
            String status = problems() == 0 ? Component.translatable("formwork.gui.check_ok").getString()
                    : Component.translatable("formwork.gui.check_bad").getString() + "  " + String.join(", ", checks.stream().filter(c -> !c.ok())
                    .map(c -> setName(c.set()).getString() + " " + c.actual() + "/" + c.required()).toList());
            status = font.plainSubstrByWidth(status, vw - 40);
            int sw = font.width(status) + 10, sy = vy + vh - 28;
            g.fill(vx + 4, sy, vx + 4 + sw, sy + 13, problems() == 0 ? 0xD0153A26 : 0xD04A1818);
            g.renderOutline(vx + 4, sy, sw, 13, problems() == 0 ? GREEN : RED);
            g.drawString(font, status, vx + 9, sy + 3, problems() == 0 ? GREEN : RED, false);
        }
        // layer slider: top = all layers
        int sx = sliderX(), st = sliderTop(), sb = sliderBottom(), layers = (int) bounds.dy();
        g.fill(sx + 3, st, sx + 7, sb, CELL);
        int shownLayers = layer == 0 ? layers : layer;
        int ky = sb - (sb - st) * shownLayers / Math.max(1, layers);
        g.fill(sx, ky - 3, sx + 10, ky + 3, draggingSlider || in(mx, my, sx - 2, st - 4, 14, sb - st + 8) ? ACCENT : BTN_ON);
        String lt = layer == 0 ? Component.translatable("formwork.gui.layers_all").getString() : layer + "/" + layers;
        g.drawString(font, lt, vx + vw - 4 - font.width(lt), sb + 6, DIM, false); // under the slider, kept inside the view
    }

    /** The block a slot has now: the player's choice, else the blueprint's. */
    private String blockAt(BlockPos pos) {
        return overrides.getOrDefault(pos, base.get(pos).id());
    }

    /** Edits this slot in the side panel (Variants tab), keeping the view clear. */
    private void select(BlockPos pos) {
        selectedSlot = pos;
        if (tabs.contains(Tab.VARIANTS)) tab = Tab.VARIANTS;
        lines = buildLines();
    }

    private void choose(String block) {
        if (block.equals(base.get(selectedSlot).id())) overrides.remove(selectedSlot);
        else overrides.put(selectedSlot, block);
        rebuild(true);
    }

    /** Choice rows for a slot: one per set of its family; a set with columns (void miner modifiers) splits into one row per column. */
    private List<Map.Entry<Component, List<String>>> choiceRows(BlockPos pos) {
        List<Map.Entry<Component, List<String>>> rows = new ArrayList<>();
        for (VariantSet set : Catalog.client.family(slotMap.get(pos), currentId())) {
            int cols = set.columns(), full = cols > 1 ? set.blocks().size() / cols * cols : 0;
            if (full == 0) {
                rows.add(Map.entry(setName(set), set.blocks()));
                continue;
            }
            for (int c = 0; c < cols; c++) {
                List<String> row = new ArrayList<>();
                for (int i = c; i < full; i += cols) row.add(set.blocks().get(i));
                rows.add(Map.entry(sharedName(row), row));
            }
            if (full < set.blocks().size()) rows.add(Map.entry(Component.translatable("formwork.gui.other"), set.blocks().subList(full, set.blocks().size())));
        }
        return rows;
    }

    /** "Rubetine Energy Modifier", "Aurantium Energy Modifier" -> "Energy Modifier". */
    private static Component sharedName(List<String> blocks) {
        List<List<String>> names = blocks.stream().map(b -> List.of(iconOf(b).getHoverName().getString().split(" "))).toList();
        List<String> shared = reversed(Catalog.sharedWords(names.stream().map(EditorScreen::reversed).toList()));
        return Component.literal(String.join(" ", shared));
    }

    /** The selected block: its name and "all like this", then the choices; a choice applies at once. */
    private void selectedLines(List<Line> out) {
        BlockPos pos = selectedSlot;
        String now = blockAt(pos), original = base.get(pos).id();
        List<BlockPos> same = slotsOf(slotMap.get(pos).id());
        Component all = Component.translatable("formwork.gui.all_like", same.size());
        out.add(new Line() {
            @Override public int height() { return 22; }

            @Override public void render(GuiGraphics g, int x, int y, int w, int mx, int my) {
                g.fill(x - 3, y, x + w + 3, y + height(), 0x334FD1FF);
                g.renderItem(iconOf(now), x, y + 3);
                int bw = font.width(all) + 8;
                g.drawString(font, font.plainSubstrByWidth(iconOf(now).getHoverName().getString(), w - 22 - bw), x + 19, y + 7, TEXT, false);
                if (Ui.button(g, font, x + w - bw, y + 4, bw, 15, all, false, mx, my))
                    setTooltipForNextRenderPass(Component.translatable("formwork.gui.all_like_tip"));
            }

            @Override public boolean click(int x, int y, int w, double mx, double my, int button) {
                int bw = font.width(all) + 8;
                if (!in(mx, my, x + w - bw, y + 4, bw, 15)) return false;
                for (BlockPos p : same) {
                    if (now.equals(base.get(p).id())) overrides.remove(p);
                    else overrides.put(p, now);
                }
                rebuild(true);
                return true;
            }
        });
        for (var row : choiceRows(pos)) {
            out.add(text(row.getKey(), DIM));
            List<String> blocks = row.getValue();
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
                                + (b.equals(original) ? "\n" + Component.translatable("formwork.gui.pick_default").getString() : "")));
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
            Component label = Component.translatable("formwork.gui.tab." + t.name().toLowerCase(Locale.ROOT));
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
            if (missingKinds() > 0) {
                showTab("MATERIALS");
                return true;
            }
            Net.CHANNEL.sendToServer(new Net.UpdatePlaced(editing.id(), selection().save(), true));
            onClose();
            return true;
        }
        if (entry.members().size() > 1) {
            int[] t = tierBox();
            int next = in(mx, my, t[0], y, 15, 15) ? tier - 1 : in(mx, my, t[2], y, 15, 15) ? tier + 1 : tier;
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

    private int ticks;

    /** Keeps the material report (ME status, counts) fresh while the editor stays open, e.g. when walking into ME range. */
    @Override
    public void tick() {
        if (++ticks % 40 == 0)
            Net.CHANNEL.sendToServer(editing != null ? new Net.UpdatePlaced(editing.id(), selection().save(), false) : new Net.SetSelection(selection().save()));
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
