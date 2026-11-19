package dev.sylviromi.formwork.client;

import dev.sylviromi.formwork.Formwork;
import dev.sylviromi.formwork.build.Materials;
import dev.sylviromi.formwork.build.Resolver;
import dev.sylviromi.formwork.item.PlacerItem;
import dev.sylviromi.formwork.structure.BlockSpec;
import dev.sylviromi.formwork.structure.Catalog;
import dev.sylviromi.formwork.structure.Selection;
import dev.sylviromi.formwork.structure.gen.Param;
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
    private static final int PER_PAGE = 27, W = 162, PREVIEW_H = 64, NAME_H = 12;

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

    private static final class Category implements IRecipeCategory<Recipe> {
        private final IDrawable icon;
        private final Ui.View view = new Ui.View();
        /** Meshes of the last few previews shown; GPU buffers, so closed when they drop out. */
        private final Map<String, StructureMesh> meshes = new LinkedHashMap<>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, StructureMesh> eldest) {
                if (size() <= 6) return false;
                eldest.getValue().close();
                return true;
            }
        };
        private final Map<String, Ui.Bounds> bounds = new HashMap<>();

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
            return PREVIEW_H + NAME_H + 3 * 18;
        }

        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, Recipe recipe, IFocusGroup focuses) {
            for (int i = 0; i < recipe.materials().size(); i++)
                builder.addSlot(RecipeIngredientRole.INPUT, (i % 9) * 18 + 1, PREVIEW_H + NAME_H + (i / 9) * 18 + 1).addItemStack(recipe.materials().get(i));
        }

        @Override
        public void draw(Recipe recipe, IRecipeSlotsView slots, GuiGraphics g, double mouseX, double mouseY) {
            g.fill(0, 0, W, PREVIEW_H, Ui.VIEW);
            StructureMesh mesh = meshes.get(recipe.id());
            if (mesh == null) {
                Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
                defaultSpecs(Catalog.client, recipe.id()).forEach((p, spec) -> {
                    BlockState s = Resolver.state(spec);
                    if (s != null) blocks.put(p, s);
                });
                if (!blocks.isEmpty()) {
                    mesh = new StructureMesh(blocks);
                    meshes.put(recipe.id(), mesh);
                    bounds.put(recipe.id(), Ui.Bounds.of(blocks.keySet()));
                }
            }
            if (mesh != null) view.draw(g, mesh, 0, 0, W, PREVIEW_H, bounds.get(recipe.id()));
            var font = Minecraft.getInstance().font;
            String page = recipe.pages() > 1 ? " " + (recipe.page() + 1) + "/" + recipe.pages() : "";
            g.drawString(font, font.plainSubstrByWidth(recipe.name().getString(), W - font.width(page)) + page, 0, PREVIEW_H + 2, 0xFF404040, false);
        }
    }
}
