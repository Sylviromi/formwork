package dev.sylviromi.formwork.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.sylviromi.formwork.Formwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads blueprints from data/<ns>/formwork/structures/** (vanilla structure-block .nbt files), and for now Multi Builder Tool's
 * data/<ns>/mbtool_structures/** too (see LEGACY),
 * and variant sets from data/<ns>/formwork/variants/*.json ({"blocks": ["mod:a", "mod:b"]}).
 */
public class CatalogLoader extends SimplePreparableReloadListener<Catalog> {
    private static final String STRUCTURES = "formwork/structures";
    /**
     * Patch-work, not part of the design: Multi Builder Tool's folder, read so packs that ship structures for that mod (Project
     * Infinity) work unchanged. shortcut: drop it once those packs move their files to formwork/structures.
     */
    private static final String LEGACY = "mbtool_structures";

    @Override
    protected Catalog prepare(ResourceManager rm, ProfilerFiller profiler) {
        Catalog catalog = new Catalog();
        int skipped = 0;
        int legacy = 0;
        for (String folder : List.of(STRUCTURES, LEGACY)) {
            for (Map.Entry<ResourceLocation, Resource> e : rm.listResources(folder, rl -> rl.getPath().endsWith(".nbt")).entrySet()) {
                ResourceLocation rl = e.getKey();
                String path = rl.getPath().substring(folder.length() + 1, rl.getPath().length() - 4);
                String id = rl.getNamespace() + ":" + path;
                try (InputStream in = e.getValue().open()) {
                    CompoundTag tag = NbtIo.readCompressed(in);
                    Blueprint bp = Blueprint.fromStructureNbt(id, tag);
                    String missing = bp.blocks().values().stream().map(BlockSpec::id)
                            .filter(b -> !isBlock(b)).findFirst().orElse(null);
                    if (missing != null || bp.blocks().isEmpty()) {
                        skipped++;
                        Formwork.LOG.debug("Skipping blueprint {}: unknown block {}", id, missing);
                        continue;
                    }
                    catalog.addBlueprint(bp);
                    if (folder.equals(LEGACY)) legacy++;
                } catch (Exception ex) {
                    Formwork.LOG.warn("Could not read blueprint {}", rl, ex);
                }
            }
        }
        for (Map.Entry<ResourceLocation, Resource> e : rm.listResources("formwork/variants", rl -> rl.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation rl = e.getKey();
            String id = rl.getNamespace() + ":" + rl.getPath().substring("formwork/variants/".length(), rl.getPath().length() - 5);
            try (Reader r = e.getValue().openAsReader()) {
                var json = JsonParser.parseReader(r).getAsJsonObject();
                JsonArray arr = json.getAsJsonArray("blocks");
                String match = json.has("match") ? json.get("match").getAsString() : "";
                List<String> blocks = new ArrayList<>();
                for (JsonElement b : arr) {
                    String block = b.getAsString().trim();
                    if (isBlock(block)) blocks.add(block);
                }
                int columns = json.has("columns") ? json.get("columns").getAsInt() : 0;
                String family = json.has("family") ? json.get("family").getAsString() : "";
                boolean walls = json.has("walls") && json.get("walls").getAsBoolean();
                if (blocks.size() > 1) catalog.addVariants(new VariantSet(id, List.copyOf(blocks), match, columns, family, walls));
            } catch (Exception ex) {
                Formwork.LOG.warn("Could not read variant set {}", rl, ex);
            }
        }
        Formwork.LOG.info("Loaded {} blueprints ({} skipped for missing mods) and {} variant sets",
                catalog.blueprints.size(), skipped, catalog.variants.size());
        if (legacy > 0) Formwork.LOG.info("{} of them from mbtool_structures (Multi Builder Tool's folder, read for compatibility for now; "
                + "move them to data/<namespace>/formwork/structures)", legacy);
        return catalog;
    }

    private static boolean isBlock(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && ForgeRegistries.BLOCKS.containsKey(rl);
    }

    @Override
    protected void apply(Catalog catalog, ResourceManager rm, ProfilerFiller profiler) {
        Catalog.server = catalog;
    }
}
