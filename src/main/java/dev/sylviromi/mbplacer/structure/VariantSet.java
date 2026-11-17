package dev.sylviromi.mbplacer.structure;

import java.util.List;

/**
 * Blocks that may replace each other. match: only applies to entries whose file name contains it (empty = everywhere);
 * needed when a block (e.g. the void miner null modifier) belongs to several sets.
 * columns: grid width in the GUI (e.g. 3 for tier rows of energy/speed/item modifiers), 0 = fit the panel.
 * family: sets with the same family may take each other's slots (e.g. all Masterful Machinery ports, since MM layouts
 * use portsAnywhere); the structure then still needs as many blocks of each set as the blueprint has. Empty: the set alone.
 */
public record VariantSet(String id, List<String> blocks, String match, int columns, String family) {
    public VariantSet(String id, List<String> blocks, String match, int columns) {
        this(id, blocks, match, columns, "");
    }

    public boolean appliesTo(String entryId) {
        return match.isEmpty() || Catalog.shortName(entryId).contains(match);
    }
}
