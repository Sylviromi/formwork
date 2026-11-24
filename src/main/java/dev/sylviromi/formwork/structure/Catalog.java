package dev.sylviromi.formwork.structure;

import dev.sylviromi.formwork.structure.gen.*;
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
            new ThermoelectricBoiler(), new InductionMatrix(), new DynamicTank(), new ThermalEvaporation(),
            new NuclearCraft.MoltenSaltReactor(), new NuclearCraft.FissionReactor(), new NuclearCraft.HeatExchanger(), new NuclearCraft.Turbine());
    private static final Pattern TIER = Pattern.compile("^(.*?)_?tier_?(\\d+)(.*)$");

    /** One row in the list: a lone blueprint, a group of tiers, or a generator. Members are ids, lowest tier first. */
    public record Entry(String key, List<String> members) {
    }

    public final Map<String, Blueprint> blueprints = new LinkedHashMap<>();
    public final Map<String, VariantSet> variants = new LinkedHashMap<>();
    private List<Entry> entries;

    public void addBlueprint(Blueprint bp) {
        blueprints.put(bp.id(), bp);
        entries = null;
    }

    public void addVariants(VariantSet set) {
        variants.put(set.id(), set);
        setCounts = null;
    }

    private Map<String, Integer> setCounts;

    /** How many variant sets list this block. */
    private int setsWith(String block) {
        if (setCounts == null) {
            Map<String, Integer> m = new HashMap<>();
            variants.values().forEach(s -> s.blocks().forEach(b -> m.merge(b, 1, Integer::sum)));
            setCounts = m;
        }
        return setCounts.getOrDefault(block, 0);
    }

    public Optional<Generator> generator(String id) {
        return generators().stream().filter(g -> g.id().equals(id)).findFirst();
    }

    public static List<Generator> generators() {
        return ALL_GENERATORS.stream().filter(g -> ModList.get() == null || ModList.get().isLoaded(g.requiredMod())).toList();
    }

    /** variantOf without knowing the structure's blocks: wall sets never apply. */
    public VariantSet variantOf(String blockId, String entryId) {
        return variantOf(blockId, entryId, Set.of());
    }

    /**
     * The variant set a block belongs to within an entry whose blocks are `present`. Sets with a match win; else the set
     * with most of its blocks in the structure, so shared blocks (structural glass) go to the multiblock they are part of,
     * whatever the structure file is called. A wall set needs one of its own blocks there (one no other set lists, like
     * its casing), so glass in some other multiblock or a decoration is left alone.
     */
    public VariantSet variantOf(String blockId, String entryId, Set<String> present) {
        VariantSet best = null;
        int bestScore = 0;
        for (VariantSet s : variants.values()) {
            if (!s.blocks().contains(blockId) || !s.appliesTo(entryId)) continue;
            if (!s.match().isEmpty()) return s;
            if (s.walls() && s.blocks().stream().noneMatch(b -> present.contains(b) && setsWith(b) == 1)) continue;
            int score = (int) s.blocks().stream().filter(present::contains).count();
            if (best == null || score > bestScore) {
                best = s;
                bestScore = score;
            }
        }
        return best;
    }

    /** The sets whose blocks may go into a slot of this set within an entry: its family, or just itself. */
    public List<VariantSet> family(VariantSet set, String entryId) {
        if (set.family().isEmpty()) return List.of(set);
        return variants.values().stream().filter(s -> s.family().equals(set.family()) && s.appliesTo(entryId)).toList();
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

    /** "auto_sieve-tier" -> "Auto Sieve Tier": the fallback when nothing translates a name. */
    public static String prettify(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("[_\\-]+")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /** The words every list starts with. */
    public static List<String> sharedWords(List<List<String>> lists) {
        List<String> first = lists.get(0);
        int n = 0;
        while (n < first.size()) {
            int i = n;
            if (!lists.stream().allMatch(l -> l.size() > i && l.get(i).equals(first.get(i)))) break;
            n++;
        }
        return first.subList(0, n);
    }

    /** Last path part of an id, used for lang keys. */
    public static String shortName(String id) {
        return id.substring(Math.max(id.lastIndexOf('/'), id.indexOf(':')) + 1);
    }
}
