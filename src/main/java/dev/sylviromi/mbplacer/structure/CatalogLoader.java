package dev.sylviromi.mbplacer.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.sylviromi.mbplacer.MbPlacer;
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
 * Reads blueprints from data/<ns>/mbplacer/structures/** and the legacy data/<ns>/mbtool_structures/**,
 * and variant sets from data/<ns>/mbplacer/variants/*.json ({"blocks": ["mod:a", "mod:b"]}).
 */
public class CatalogLoader extends SimplePreparableReloadListener<Catalog> {
    private static final List<String> STRUCTURE_FOLDERS = List.of("mbplacer/structures", "mbtool_structures");

    @Override
    protected Catalog prepare(ResourceManager rm, ProfilerFiller profiler) {
        Catalog catalog = new Catalog();
        int skipped = 0;
        for (String folder : STRUCTURE_FOLDERS) {
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
                        MbPlacer.LOG.debug("Skipping blueprint {}: unknown block {}", id, missing);
                        continue;
                    }
                    catalog.addBlueprint(bp);
                } catch (Exception ex) {
                    MbPlacer.LOG.warn("Could not read blueprint {}", rl, ex);
                }
            }
        }
        for (Map.Entry<ResourceLocation, Resource> e : rm.listResources("mbplacer/variants", rl -> rl.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation rl = e.getKey();
            String id = rl.getNamespace() + ":" + rl.getPath().substring("mbplacer/variants/".length(), rl.getPath().length() - 5);
            try (Reader r = e.getValue().openAsReader()) {
                var json = JsonParser.parseReader(r).getAsJsonObject();
                JsonArray arr = json.getAsJsonArray("blocks");
                String match = json.has("match") ? json.get("match").getAsString() : "";
                List<String> blocks = new ArrayList<>();
                for (JsonElement b : arr) {
                    String block = b.getAsString().trim();
                    if (isBlock(block)) blocks.add(block);
                }
                if (blocks.size() > 1) catalog.addVariants(new VariantSet(id, List.copyOf(blocks), match));
            } catch (Exception ex) {
                MbPlacer.LOG.warn("Could not read variant set {}", rl, ex);
            }
        }
        MbPlacer.LOG.info("Loaded {} blueprints ({} skipped for missing mods) and {} variant sets",
                catalog.blueprints.size(), skipped, catalog.variants.size());
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
