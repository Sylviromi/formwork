package dev.sylviromi.formwork.client;

import dev.sylviromi.formwork.Formwork;
import dev.sylviromi.formwork.build.Materials;
import dev.sylviromi.formwork.build.Resolver;
import dev.sylviromi.formwork.item.PlacerItem;
import dev.sylviromi.formwork.structure.BlockSpec;
import dev.sylviromi.formwork.structure.Catalog;
import dev.sylviromi.formwork.structure.Selection;
import dev.sylviromi.formwork.structure.gen.Param;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * JEI: every structure the server sends is a recipe of its materials, with a spinning preview. U on a block lists the
 * structures that use it, R on the tool lists them all. Tiers are one recipe each; generators show their default settings.
 * Only loaded by JEI.
 */
@JeiPlugin
public final class JeiStructures implements IModPlugin {
    static final RecipeType<Recipe> TYPE = RecipeType.create(Formwork.ID, "structures", Recipe.class);
    /** Materials per page: three rows of nine; bigger structures continue on the next page. */
    private static final int PER_PAGE = 24, COLS = 8, PITCH = 20, W = 178, PREVIEW_H = 84, NAME_H = 14, P = Ui.INSET, CW = W - 2 * P, GRID_Y = P + PREVIEW_H + NAME_H;

    /** One page of a structure's materials. */
    record Recipe(String id, Component name, List<ItemStack> materials, int page, int pages) {
    }

    @Nullable
    private static IJeiRuntime runtime;
    /** The catalog the shown recipes came from, and how many entries it had: the server streams blueprints one by one. */
    @Nullable
    private static Catalog shownFor;
    private static int shownSize = -1, stableTicks;
    private static List<Recipe> shown = List.of();

    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(Formwork.ID, "structures");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new Category(registration.getJeiHelpers().getGuiHelper().createDrawableItemStack(new ItemStack(Formwork.PLACER.get()))));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(new ItemStack(Formwork.PLACER.get()), TYPE);
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        boolean first = runtime == null;
        runtime = jeiRuntime;
        shownFor = null; // a new runtime has none of ours yet
        shown = List.of();
        if (first) MinecraftForge.EVENT_BUS.addListener(JeiStructures::onClientTick);
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    /** Dev run: opens JEI on every structure, or on those using a stack. */
    static void devShow(@Nullable ItemStack uses) {
        if (runtime == null) return;
        if (uses == null) runtime.getRecipesGui().showTypes(List.of(TYPE));
        else runtime.getRecipesGui().show(runtime.getJeiHelpers().getFocusFactory().createFocus(RecipeIngredientRole.INPUT, VanillaTypes.ITEM_STACK, uses));
    }

    /** Refreshes the recipes once the catalog has not changed for a second (it arrives in many packets). */
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || runtime == null) return;
        Catalog c = Catalog.client;
        int size = c.entries().size();
        if (c != lastCatalog || size != lastSize) {
            lastCatalog = c;
            lastSize = size;
            stableTicks = 0;
            return;
        }
        if ((c == shownFor && size == shownSize) || ++stableTicks < 20) return;
        if (!shown.isEmpty()) runtime.getRecipeManager().hideRecipes(TYPE, shown);
        shown = recipes(c);
        if (!shown.isEmpty()) runtime.getRecipeManager().addRecipes(TYPE, shown);
        shownFor = c;
        shownSize = size;
    }

    private static int lastSize = -1;
    @Nullable
    private static Catalog lastCatalog;

    static List<Recipe> recipes(Catalog c) {
        List<Recipe> out = new ArrayList<>();
        for (Catalog.Entry e : c.entries())
            for (String id : e.members()) {
                List<ItemStack> materials = new ArrayList<>();
                Materials.neededForSpecs(defaultSpecs(c, id).values()).forEach((item, n) -> materials.add(new ItemStack(item, n)));
                if (materials.isEmpty()) continue;
                materials.sort(Comparator.comparingInt(ItemStack::getCount).reversed());
                int pages = (materials.size() + PER_PAGE - 1) / PER_PAGE;
                Component name = PlacerItem.displayName(id);
                for (int p = 0; p < pages; p++)
                    out.add(new Recipe(id, name, List.copyOf(materials.subList(p * PER_PAGE, Math.min(materials.size(), (p + 1) * PER_PAGE))), p, pages));
            }
        return out;
    }

    private static Map<BlockPos, BlockSpec> defaultSpecs(Catalog c, String id) {
        int[] params = c.generator(id).map(g -> g.params().stream().mapToInt(Param::def).toArray()).orElse(new int[0]);
        Map<BlockPos, BlockSpec> specs = Resolver.specs(c, new Selection(id, params, Map.of()));
        return specs == null ? Map.of() : specs;
    }

    /** A structure's preview: its mesh, size and blocks, built the first time its recipe is drawn. */
    private record Preview(StructureMesh mesh, Ui.Bounds bounds, Map<BlockPos, BlockState> blocks, Map<Item, StructureMesh[]> split) {
        /** The blocks made of this item and the rest, as meshes. */
        StructureMesh[] split(Item item) {
            return split.computeIfAbsent(item, i -> {
                Map<BlockPos, BlockState> solo = new LinkedHashMap<>(), rest = new LinkedHashMap<>();
                blocks.forEach((pos, st) -> (st.getBlock().asItem() == i ? solo : rest).put(pos, st));
                return new StructureMesh[]{new StructureMesh(solo), new StructureMesh(rest)};
            });
        }
    }

    /** A dark cell behind a material, like the preview above it. */
    private static final IDrawable CELL = new IDrawable() {
        @Override
        public int getWidth() {
            return 18;
        }

        @Override
        public int getHeight() {
            return 18;
        }

        @Override
        public void draw(GuiGraphics g, int x, int y) {
            g.fill(x, y, x + 18, y + 18, 0xFF3A4350);
            g.renderOutline(x, y, 18, 18, 0xFF566170);
        }
    };

    /** The needed amount in the corner of a slot, above the item: 146, 1.2k, 12k. */
    private record CountOverlay(int n) implements IDrawable {
        @Override
        public int getWidth() {
            return 16;
        }

        @Override
        public int getHeight() {
            return 16;
        }

        @Override
        public void draw(GuiGraphics g, int x, int y) {
            if (n <= 1) return;
            String text = n < 1000 ? Integer.toString(n) : n < 10000 ? String.format(Locale.ROOT, "%.1fk", n / 1000f) : n / 1000 + "k";
            var font = Minecraft.getInstance().font;
            g.pose().pushPose();
            g.pose().translate(0, 0, 160);
            g.drawString(font, text, x + 17 - font.width(text), y + 9, 0xFFFFFFFF, true);
            g.pose().popPose();
        }
    }

    private static final class Category implements IRecipeCategory<Recipe> {
        private final IDrawable icon;
        private final Ui.View view = new Ui.View();
        /** Previews of the last few recipes shown; the meshes are GPU buffers, so closed when they drop out. */
        private final Map<String, Preview> previews = new LinkedHashMap<>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Preview> eldest) {
                if (size() <= 6) return false;
                eldest.getValue().mesh().close();
                return true;
            }
        };
        /** Hovering the preview freezes the spin so blocks can be pointed at; a click keeps it frozen. */
        private boolean paused, hovering;
        @Nullable
        private BlockPos hoverBlock;
        private double originX, originY;

        Category(IDrawable icon) {
            this.icon = icon;
            view.spin = true;
        }

        @Override
        public RecipeType<Recipe> getRecipeType() {
            return TYPE;
        }

        @Override
        public Component getTitle() {
            return Formwork.PLACER.get().getDescription();
        }

        @Override
        public int getWidth() {
            return W;
        }

        @Override
        public int getHeight() {
            return GRID_Y + 3 * PITCH + P - 2;
        }

        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, Recipe recipe, IFocusGroup focuses) {
            for (int i = 0; i < recipe.materials().size(); i++) {
                ItemStack need = recipe.materials().get(i);
                // the count is drawn by the overlay (compact, over the corner), so JEI gets a single item and its own number stays off
                builder.addSlot(RecipeIngredientRole.INPUT, P + (i % COLS) * PITCH + 1, GRID_Y + (i / COLS) * PITCH + 1)
                        .addItemStack(need.copyWithCount(1))
                        .setBackground(CELL, -1, -1)
                        .setOverlay(new CountOverlay(need.getCount()), 0, 0);
            }
        }

        @Nullable
        private Preview preview(String id) {
            Preview p = previews.get(id);
            if (p != null) return p;
            Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
            defaultSpecs(Catalog.client, id).forEach((pos, spec) -> {
                BlockState st = Resolver.state(spec);
                if (st != null) blocks.put(pos, st);
            });
            if (blocks.isEmpty()) return null;
            p = new Preview(new StructureMesh(blocks), Ui.Bounds.of(blocks.keySet()), blocks, new java.util.HashMap<>());
            previews.put(id, p);
            return p;
        }

        private static boolean overPreview(double mx, double my) {
            return Ui.in(mx, my, P, P, CW, PREVIEW_H);
        }

        @Override
        public boolean handleInput(Recipe recipe, double mouseX, double mouseY, InputConstants.Key input) {
            if (input.getType() != InputConstants.Type.MOUSE || input.getValue() != 0 || !overPreview(mouseX, mouseY)) return false;
            paused = !paused;
            return true;
        }

        @Override
        public List<Component> getTooltipStrings(Recipe recipe, IRecipeSlotsView slots, double mouseX, double mouseY) {
            if (!overPreview(mouseX, mouseY)) return List.of();
            Preview p = preview(recipe.id());
            BlockState st = hoverBlock == null || p == null ? null : p.blocks().get(hoverBlock);
            List<Component> tip = new ArrayList<>();
            if (st != null) tip.add(st.getBlock().getName());
            tip.add(Component.translatable("formwork.jei.hint").withStyle(net.minecraft.ChatFormatting.GRAY));
            return tip;
        }

        @Override
        public void draw(Recipe recipe, IRecipeSlotsView slots, GuiGraphics g, double mouseX, double mouseY) {
            var origin = g.pose().last().pose().transformPosition(new org.joml.Vector3f());
            originX = origin.x;
            originY = origin.y;
            hovering = overPreview(mouseX, mouseY);
            view.spin = !paused && !hovering;
            g.fill(0, 0, W, getHeight(), Ui.PANEL);
            g.renderOutline(0, 0, W, getHeight(), Ui.BORDER);
            g.fill(P, P, P + CW, P + PREVIEW_H, Ui.VIEW);
            g.renderOutline(P, P, CW, PREVIEW_H, Ui.BORDER);
            Preview p = preview(recipe.id());
            var font = Minecraft.getInstance().font;
            if (p != null) {
                // pointing at a material lights up every block made of it in the preview
                int cx = (int) mouseX - P, cy = (int) mouseY - GRID_Y, col = cx / PITCH, row = cy / PITCH, idx = row * COLS + col;
                boolean overCell = cx >= 0 && cy >= 0 && cx % PITCH < 18 && cy % PITCH < 18 && col < COLS;
                if (overCell && idx < recipe.materials().size()) {
                    var item = recipe.materials().get(idx).getItem();
                    var parts = p.split(item);
                    view.solo = parts[0];
                    view.rest = parts[1];
                } else view.solo = view.rest = null;
                hoverBlock = hovering ? view.pick(mouseX + originX, mouseY + originY, p.blocks().keySet()) : null;
                view.draw(g, p.mesh(), P, P, CW, PREVIEW_H, p.bounds(), hoverBlock, null);
                var b = p.bounds();
                String size = (int) b.dx() + " \u00d7 " + (int) b.dy() + " \u00d7 " + (int) b.dz();
                g.drawString(font, size, P + Ui.INSET, P + PREVIEW_H - 9 - 1, Ui.DIM, false);
                String blocks = Component.translatable("formwork.gui.blocks", p.blocks().size()).getString();
                g.drawString(font, blocks, P + CW - Ui.INSET - font.width(blocks), P + PREVIEW_H - 9 - 1, Ui.DIM, false);
                if (paused) {
                    g.fill(P + Ui.INSET, P + Ui.INSET, P + Ui.INSET + 2, P + Ui.INSET + 7, Ui.YELLOW);
                    g.fill(P + Ui.INSET + 4, P + Ui.INSET, P + Ui.INSET + 6, P + Ui.INSET + 7, Ui.YELLOW);
                }
            }
            String page = recipe.pages() > 1 ? " " + (recipe.page() + 1) + "/" + recipe.pages() : "";
            String count = Component.translatable("formwork.jei.materials", recipe.materials().size()).getString();
            int ty = P + PREVIEW_H + Ui.textY(0, NAME_H) + 1;
            String name = font.plainSubstrByWidth(recipe.name().getString(), CW - font.width(count) - font.width(page) - 12);
            g.fill(P, ty - 1, P + 2, ty + 8, Ui.ACCENT);
            g.drawString(font, name, P + 6, ty, Ui.TEXT, false);
            g.drawString(font, page, P + 6 + font.width(name), ty, Ui.DIM, false);
            g.drawString(font, count, P + CW - font.width(count), ty, Ui.DIM, false);
        }
    }
}
