package dev.sylviromi.mbplacer.client;

import dev.sylviromi.mbplacer.build.Resolver;
import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.structure.BlockSpec;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import dev.sylviromi.mbplacer.structure.gen.Param;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

import static dev.sylviromi.mbplacer.client.Ui.*;

/** Picks a multiblock: search, mod categories on the left, cards with 3D thumbnails. A click opens the editor. */
public class BrowserScreen extends Screen {
    private static final int PAD = 6, CAT_W = 112, CARD_W = 112, CARD_H = 92, GAP = 6, THUMB_H = 62, MAX_THUMB_BLOCKS = 5000;
    private static final String ALL = "";

    /** What a card shows; blocks: the first member's default blocks (thumbnail). */
    private record Info(Catalog.Entry entry, String name, String category, String subtitle, Map<BlockPos, BlockState> blocks, Bounds bounds, ItemStack icon) {
    }

    private static Catalog infoCatalog;
    private static int infoSize;
    private static List<Info> infos = List.of();
    private static String category = ALL;
    private static String query = "";

    private final Map<String, StructureMesh> thumbs = new HashMap<>();
    private final View still = new View(), spinning = new View();
    private String hovered = "";
    private EditBox search;
    private List<Info> shown = List.of();
    private Map<String, Integer> categories = Map.of();
    private int x0, y0, w, h;
    /** Kept between visits, like the search and category. */
    private static int scroll;

    public BrowserScreen() {
        super(Component.translatable("item.mbplacer.placer"));
        spinning.spin = true;
    }

    private static List<Info> infos() {
        Catalog c = Catalog.client;
        if (c == infoCatalog && c.entries().size() == infoSize) return infos;
        List<Info> out = new ArrayList<>();
        for (Catalog.Entry e : c.entries()) {
            String id = e.members().get(0);
            int[] params = c.generator(id).map(g -> g.params().stream().mapToInt(Param::def).toArray()).orElse(new int[0]);
            Map<BlockPos, BlockSpec> specs = Resolver.specs(c, new Selection(id, params, Map.of()));
            Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
            Map<BlockState, Integer> perState = new HashMap<>();
            if (specs != null) specs.forEach((p, spec) -> {
                BlockState s = Resolver.state(spec);
                if (s == null) return;
                blocks.put(p, s);
                perState.merge(s, 1, Integer::sum);
            });
            // the controller's mod, else the rarest modded block's: casings often come from decoration mods
            String mod = perState.entrySet().stream()
                    .sorted(Comparator.comparing((Map.Entry<BlockState, Integer> en) -> !ForgeRegistries.BLOCKS.getKey(en.getKey().getBlock()).getPath().contains("controller"))
                            .thenComparing(Map.Entry::getValue))
                    .map(en -> ForgeRegistries.BLOCKS.getKey(en.getKey().getBlock()).getNamespace()).filter(ns -> !ns.equals("minecraft"))
                    .findFirst().orElse("minecraft");
            String category = ModList.get().getModContainerById(mod).map(m -> m.getModInfo().getDisplayName()).orElse(Catalog.prettify(mod));
            String subtitle = e.members().size() > 1 ? Component.translatable("mbplacer.gui.tiers", e.members().size()).getString() : "";
            // the rarest block with an item is usually the controller
            ItemStack icon = perState.entrySet().stream().sorted(Map.Entry.comparingByValue())
                    .map(en -> new ItemStack(en.getKey().getBlock())).filter(s -> !s.is(Items.AIR)).findFirst().orElse(ItemStack.EMPTY);
            out.add(new Info(e, PlacerItem.entryName(e).getString(), category, subtitle, blocks, Bounds.of(blocks.keySet()), icon));
        }
        // one category per mod family: "Mekanism: Generators" joins "Mekanism"
        Set<String> names = new HashSet<>();
        out.forEach(i -> names.add(i.category()));
        out.replaceAll(i -> names.stream().filter(n -> !n.equals(i.category()) && i.category().startsWith(n)).min(Comparator.comparingInt(String::length))
                .map(n -> new Info(i.entry(), i.name(), n, i.subtitle(), i.blocks(), i.bounds(), i.icon())).orElse(i));
        infoCatalog = c;
        infoSize = c.entries().size();
        return infos = out;
    }

    @Override
    protected void init() {
        x0 = 8;
        y0 = 8;
        w = width - 16;
        h = height - 16;
        search = new EditBox(font, x0 + PAD, y0 + PAD, w - 2 * PAD, 16, Component.translatable("gui.recipebook.search_hint"));
        search.setHint(Component.translatable("gui.recipebook.search_hint").withStyle(s -> s.withColor(DIM)));
        search.setValue(query);
        search.setResponder(q -> {
            query = q;
            refilter();
            scroll = 0;
        });
        addRenderableWidget(search);
        setInitialFocus(search);
        refilter();
        scroll = Math.min(scroll, maxScroll());
    }

    private void refilter() {
        Map<String, Integer> cats = new TreeMap<>();
        infos().forEach(i -> cats.merge(i.category(), 1, Integer::sum));
        categories = cats;
        if (!category.equals(ALL) && !cats.containsKey(category)) category = ALL;
        String q = query.toLowerCase(Locale.ROOT);
        shown = infos().stream().filter(i -> category.equals(ALL) || i.category().equals(category))
                .filter(i -> q.isEmpty() || i.name().toLowerCase(Locale.ROOT).contains(q) || i.entry().key().toLowerCase(Locale.ROOT).contains(q)).toList();
    }

    void openForDev(String key) {
        infos().stream().filter(i -> i.entry().key().equals(key)).findFirst().ifPresent(i -> minecraft.setScreen(new EditorScreen(i.entry())));
    }

    // ---------- geometry ----------

    private int top() { return y0 + PAD + 22; }
    private int bottom() { return y0 + h - PAD; }
    private int gridX() { return x0 + PAD + CAT_W + PAD; }
    private int gridW() { return x0 + w - PAD - gridX(); }
    private int cols() { return Math.max(1, (gridW() + GAP) / (CARD_W + GAP)); }
    private int cardW() { return (gridW() - (cols() - 1) * GAP) / cols(); }

    // ---------- rendering ----------

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        g.fill(x0, y0, x0 + w, y0 + h, BG);
        g.renderOutline(x0, y0, w, h, BORDER);
        int cy = top();
        button(g, font, x0 + PAD, cy, CAT_W, 16, Component.literal(Component.translatable("gui.socialInteractions.tab_all").getString() + "  " + infos().size()), category.equals(ALL), mx, my);
        for (var c : categories.entrySet()) {
            cy += 18;
            String label = font.plainSubstrByWidth(c.getKey(), CAT_W - 26) + "  " + c.getValue();
            button(g, font, x0 + PAD, cy, CAT_W, 16, Component.literal(label), category.equals(c.getKey()), mx, my);
        }
        renderCards(g, mx, my);
        super.render(g, mx, my, partial);
    }

    private void renderCards(GuiGraphics g, int mx, int my) {
        int top = top(), bottom = bottom(), cw = cardW(), cols = cols();
        String current = PlacerItem.selection(WorldGhost.heldTool()).entry();
        String hover = "";
        boolean built = false;
        g.enableScissor(gridX(), top, gridX() + gridW(), bottom);
        for (int i = 0; i < shown.size(); i++) {
            Info info = shown.get(i);
            int x = gridX() + (i % cols) * (cw + GAP), y = top + (i / cols) * (CARD_H + GAP) - scroll;
            if (y + CARD_H < top || y > bottom) continue;
            boolean over = in(mx, my, x, y, cw, CARD_H) && my >= top && my < bottom;
            boolean selected = info.entry().members().contains(current);
            g.fill(x, y, x + cw, y + CARD_H, over ? 0xF01C2430 : PANEL);
            g.renderOutline(x, y, cw, CARD_H, selected ? ACCENT : over ? 0xFF4A5868 : BORDER);
            g.fill(x + 3, y + 3, x + cw - 3, y + 3 + THUMB_H, VIEW);
            String key = info.entry().key();
            if (over) hover = key;
            StructureMesh mesh = thumbs.get(key);
            // build at most one thumbnail per frame so opening the browser never stalls
            if (mesh == null && !built && !info.blocks().isEmpty() && info.blocks().size() <= MAX_THUMB_BLOCKS) {
                mesh = new StructureMesh(info.blocks());
                thumbs.put(key, mesh);
                built = true;
            }
            if (mesh != null) {
                g.enableScissor(x + 3, y + 3, x + cw - 3, y + 3 + THUMB_H); // intersects with the grid's scissor
                (over ? spinning : still).draw(g, mesh, x + 3, y + 3, cw - 6, THUMB_H, info.bounds());
                g.disableScissor();
            } else if (!info.icon().isEmpty()) {
                g.renderItem(info.icon(), x + cw / 2 - 8, y + 3 + THUMB_H / 2 - 8);
            }
            g.drawString(font, font.plainSubstrByWidth(info.name(), cw - 8), x + 4, y + THUMB_H + 7, selected ? ACCENT : TEXT, false);
            int sw = font.width(info.subtitle());
            g.drawString(font, font.plainSubstrByWidth(info.category(), cw - 12 - sw), x + 4, y + THUMB_H + 18, DIM, false);
            g.drawString(font, info.subtitle(), x + cw - 4 - sw, y + THUMB_H + 18, DIM, false);
        }
        g.disableScissor();
        if (!hover.equals(hovered)) {
            hovered = hover;
            spinning.reset();
        }
        if (shown.isEmpty()) g.drawCenteredString(font, Component.translatable("mbplacer.gui.nothing_found"), gridX() + gridW() / 2, top + 30, DIM);
    }

    // ---------- input ----------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int cy = top();
        if (in(mx, my, x0 + PAD, cy, CAT_W, 16)) return pick(ALL);
        for (String c : categories.keySet()) {
            cy += 18;
            if (in(mx, my, x0 + PAD, cy, CAT_W, 16)) return pick(c);
        }
        int cw = cardW(), cols = cols();
        if (in(mx, my, gridX(), top(), gridW(), bottom() - top())) {
            int col = (int) ((mx - gridX()) / (cw + GAP)), row = (int) ((my - top() + scroll) / (CARD_H + GAP));
            int i = row * cols + col;
            if (col < cols && i >= 0 && i < shown.size()) {
                minecraft.setScreen(new EditorScreen(shown.get(i).entry()));
                return true;
            }
        }
        return false;
    }

    private boolean pick(String c) {
        category = c;
        refilter();
        scroll = 0;
        return true;
    }

    private int maxScroll() {
        int rows = (shown.size() + cols() - 1) / cols();
        return Math.max(0, rows * (CARD_H + GAP) - (bottom() - top()));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = (int) Math.max(0, Math.min(maxScroll(), scroll - delta * (CARD_H + GAP) / 2));
        return true;
    }

    @Override
    public void removed() {
        thumbs.values().forEach(StructureMesh::close);
        thumbs.clear();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
