package dev.sylviromi.mbplacer.structure;

import java.util.List;

/**
 * Blocks that may replace each other. match: only applies to entries whose file name contains it (empty = everywhere);
 * needed when a block (e.g. the void miner null modifier) belongs to several sets.
 * columns: grid width in the GUI (e.g. 3 for tier rows of energy/speed/item modifiers), 0 = fit the panel.
 */
public record VariantSet(String id, List<String> blocks, String match, int columns) {
    public boolean appliesTo(String entryId) {
        return match.isEmpty() || Catalog.shortName(entryId).contains(match);
    }
}
