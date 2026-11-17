package dev.sylviromi.mbplacer.structure;

import dev.sylviromi.mbplacer.structure.gen.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fml.ModList;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything that can be placed. The server fills one from datapacks; the client gets a copy over the network.
 * Generators are code, so both sides build them locally.
 */
public final class Catalog {
    public static Catalog server = new Catalog();
    public static Catalog client = new Catalog();

    public static Catalog side(boolean clientSide) {
        return clientSide ? client : server;
    }

    private static final List<Generator> ALL_GENERATORS = List.of(new FissionReactor(), new IndustrialTurbine(),
            new ThermoelectricBoiler(), new InductionMatrix(), new DynamicTank());
    private static final Pattern TIER = Pattern.compile("^(.*?)_?tier_?(\\d+)(.*)$");

    /** One row in the list: a lone blueprint, a group of tiers, or a generator. Members are ids, lowest tier first. */
    public record Entry(String key, List<String> members) {
    }

    /** Raw structure nbt by blueprint id, kept so the server can send it to clients unchanged. */
    public final Map<String, CompoundTag> raw = new LinkedHashMap<>();
    public final Map<String, Blueprint> blueprints = new LinkedHashMap<>();
    public final Map<String, VariantSet> variants = new LinkedHashMap<>();
    private Map<String, VariantSet> variantOfBlock;
    private List<Entry> entries;

    public void addBlueprint(String id, CompoundTag tag) {
        raw.put(id, tag);
        blueprints.put(id, Blueprint.fromStructureNbt(id, tag));
        entries = null;
    }

    public void addVariants(VariantSet set) {
        variants.put(set.id(), set);
        variantOfBlock = null;
    }

    public Optional<Generator> generator(String id) {
        return generators().stream().filter(g -> g.id().equals(id)).findFirst();
    }

    public static List<Generator> generators() {
        return ALL_GENERATORS.stream().filter(g -> ModList.get() == null || ModList.get().isLoaded(g.requiredMod())).toList();
    }

    public VariantSet variantOf(String blockId) {
        if (variantOfBlock == null) {
            variantOfBlock = new HashMap<>();
            variants.values().forEach(s -> s.blocks().forEach(b -> variantOfBlock.putIfAbsent(b, s)));
        }
        return variantOfBlock.get(blockId);
    }

    public List<Entry> entries() {
        if (entries != null) return entries;
        List<Entry> out = new ArrayList<>();
        generators().forEach(g -> out.add(new Entry(g.id(), List.of(g.id()))));
        Map<String, TreeMap<Integer, String>> groups = new LinkedHashMap<>();
        for (String id : blueprints.keySet()) {
            String[] g = groupOf(id);
            groups.computeIfAbsent(g[0], k -> new TreeMap<>()).merge(Integer.parseInt(g[1]), id, (a, b) -> a);
        }
        groups.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.add(new Entry(e.getKey(), List.copyOf(e.getValue().values()))));
        return entries = out;
    }

    /** "ns:folder/voidminer_tier3_verdium" -> ["ns:folder/voidminer", "3"]; ids without a tier get tier 0 and stay alone. */
    public static String[] groupOf(String id) {
        int slash = id.lastIndexOf('/') + 1;
        int colon = id.indexOf(':') + 1;
        int start = Math.max(slash, colon);
        Matcher m = TIER.matcher(id.substring(start));
        if (!m.matches() || m.group(1).isEmpty()) return new String[]{id, "0"};
        return new String[]{id.substring(0, start) + m.group(1), m.group(2)};
    }

    /** Last path part of an id, used for lang keys. */
    public static String shortName(String id) {
        return id.substring(Math.max(id.lastIndexOf('/'), id.indexOf(':')) + 1);
    }
}
