package dev.sylviromi.formwork.client;

import dev.sylviromi.formwork.build.Resolver;
import dev.sylviromi.formwork.item.PlacerItem;
import dev.sylviromi.formwork.structure.BlockSpec;
import dev.sylviromi.formwork.structure.Catalog;
import dev.sylviromi.formwork.structure.Selection;
import dev.sylviromi.formwork.structure.gen.Param;
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

import static dev.sylviromi.formwork.client.Ui.*;

/** Picks a multiblock: search, mod categories on the left, cards with 3D thumbnails. A click opens the editor. */
public class BrowserScreen extends Screen {
    /** Cards: a thumbnail, then the name and the category line, INSET from the card's edges, PAD apart. */
    private static final int CAT_W = 112, CARD_W = 112, THUMB_H = 62, LINE = 9, CARD_H = INSET + THUMB_H + INSET + LINE + GAP + LINE + INSET, MAX_THUMB_BLOCKS = 5000;
    private static final String ALL = "";
    private static final int STAR = 9;

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
    private static int scroll, catScroll;

    public BrowserScreen() {
        super(Component.translatable("item.formwork.placer"));
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
            String subtitle = e.members().size() > 1 ? Component.translatable("formwork.gui.tiers", e.members().size()).getString() : "";
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
        x0 = MARGIN;
        y0 = MARGIN;
        w = width - 2 * MARGIN;
        h = height - 2 * MARGIN;
        search = new EditBox(font, x0 + PAD + PAD, textY(y0 + PAD, CTRL), w - 4 * PAD, 9, Component.translatable("gui.recipebook.search_hint"));
        search.setBordered(false);
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
                .filter(i -> q.isEmpty() || i.name().toLowerCase(Locale.ROOT).contains(q) || i.entry().key().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing((Info i) -> !Memory.favourite(i.entry().key()))).toList();
    }

    void openForDev(String key) {
        infos().stream().filter(i -> i.entry().key().equals(key)).findFirst().ifPresent(i -> minecraft.setScreen(new EditorScreen(i.entry())));
    }

    // ---------- geometry ----------

    private static int starX(int cardX, int cardW) { return cardX + cardW - INSET - STAR - 2; }

    private int top() { return y0 + PAD + CTRL + PAD; }
    private int bottom() { return y0 + h - PAD; }
    private int gridX() { return x0 + PAD + CAT_W + PAD; }
    private int gridW() { return x0 + w - PAD - gridX(); }
    private int cols() { return Math.max(1, (gridW() + PAD) / (CARD_W + PAD)); }
    private int cardW() { return (gridW() - (cols() - 1) * PAD) / cols(); }
    private static final int CAT_STEP = CTRL + GAP;

    // ---------- rendering ----------

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        g.fill(x0, y0, x0 + w, y0 + h, BG);
        g.renderOutline(x0, y0, w, h, BORDER);
        field(g, x0 + PAD, y0 + PAD, w - 2 * PAD, search.isFocused());
        // categories scroll on their own when they don't fit
        g.enableScissor(x0 + PAD, top(), x0 + PAD + CAT_W, bottom());
        List<String> cats = catList();
        for (int i = 0; i < cats.size(); i++) {
            String c = cats.get(i);
            String name = c.equals(ALL) ? Component.translatable("gui.socialInteractions.tab_all").getString() : c;
            String count = " (" + (c.equals(ALL) ? infos().size() : categories.get(c)) + ")";
            String label = font.plainSubstrByWidth(name, CAT_W - 2 * PAD - font.width(count)) + count;
            boolean over = my >= top() && my < bottom();
            button(g, font, x0 + PAD, top() + i * CAT_STEP - catScroll, CAT_W, CTRL, Component.literal(label), category.equals(c), mx, over ? my : -1000);
        }
        g.disableScissor();
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
            int x = gridX() + (i % cols) * (cw + PAD), y = top + (i / cols) * (CARD_H + PAD) - scroll;
            if (y + CARD_H < top || y > bottom) continue;
            boolean over = in(mx, my, x, y, cw, CARD_H) && my >= top && my < bottom;
            boolean selected = info.entry().members().contains(current);
            g.fill(x, y, x + cw, y + CARD_H, over ? 0xF01C2430 : PANEL);
            g.renderOutline(x, y, cw, CARD_H, selected ? ACCENT : over ? 0xFF4A5868 : BORDER);
            int tx = x + INSET, ty = y + INSET, tw = cw - 2 * INSET, line1 = ty + THUMB_H + INSET, line2 = line1 + LINE + GAP;
            g.fill(tx, ty, tx + tw, ty + THUMB_H, VIEW);
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
                g.enableScissor(tx, ty, tx + tw, ty + THUMB_H); // intersects with the grid's scissor
                (over ? spinning : still).draw(g, mesh, tx, ty, tw, THUMB_H, info.bounds());
                g.disableScissor();
            } else if (!info.icon().isEmpty()) {
                g.renderItem(info.icon(), tx + tw / 2 - 8, ty + THUMB_H / 2 - 8);
            }
            boolean fav = Memory.favourite(key), overStar = in(mx, my, starX(x, cw), ty + 2, STAR, STAR) && my >= top && my < bottom;
            if (fav || over) g.drawString(font, "\u2605", starX(x, cw), ty + 2, fav ? YELLOW : overStar ? TEXT : DIM, false);
            if (overStar) setTooltipForNextRenderPass(Component.translatable("formwork.gui.favourite"));
            g.drawString(font, font.plainSubstrByWidth(info.name(), tw), tx, line1, selected ? ACCENT : TEXT, false);
            int sw = font.width(info.subtitle());
            g.drawString(font, font.plainSubstrByWidth(info.category(), tw - sw - PAD), tx, line2, DIM, false);
            g.drawString(font, info.subtitle(), tx + tw - sw, line2, DIM, false);
        }
        g.disableScissor();
        if (!hover.equals(hovered)) {
            hovered = hover;
            spinning.reset();
        }
        if (shown.isEmpty()) g.drawCenteredString(font, Component.translatable("formwork.gui.nothing_found"), gridX() + gridW() / 2, top + 30, DIM);
    }

    // ---------- input ----------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        List<String> cats = catList();
        if (my >= top() && my < bottom())
            for (int i = 0; i < cats.size(); i++)
                if (in(mx, my, x0 + PAD, top() + i * CAT_STEP - catScroll, CAT_W, CTRL)) return pick(cats.get(i));
        int cw = cardW(), cols = cols();
        if (in(mx, my, gridX(), top(), gridW(), bottom() - top())) {
            int col = (int) ((mx - gridX()) / (cw + PAD)), row = (int) ((my - top() + scroll) / (CARD_H + PAD));
            int i = row * cols + col;
            if (col < cols && i >= 0 && i < shown.size()) {
                int cx = gridX() + col * (cw + PAD), cy = top() + row * (CARD_H + PAD) - scroll;
                if (in(mx, my, starX(cx, cw), cy + INSET + 2, STAR, STAR)) {
                    Memory.toggleFavourite(shown.get(i).entry().key());
                    refilter();
                    return true;
                }
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
        return Math.max(0, rows * (CARD_H + PAD) - PAD - (bottom() - top()));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < gridX()) catScroll = (int) Math.max(0, Math.min(catList().size() * CAT_STEP - GAP - (bottom() - top()), catScroll - delta * CAT_STEP * 2));
        else scroll = (int) Math.max(0, Math.min(maxScroll(), scroll - delta * (CARD_H + PAD) / 2));
        return true;
    }

    /** "All" first, then the mod categories. */
    private List<String> catList() {
        List<String> out = new ArrayList<>(List.of(ALL));
        out.addAll(categories.keySet());
        return out;
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
