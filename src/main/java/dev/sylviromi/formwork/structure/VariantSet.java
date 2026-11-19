package dev.sylviromi.formwork.structure;

import java.util.List;

/**
 * Blocks that may replace each other. match: only applies to entries whose file name contains it (empty = everywhere);
 * needed when a block (e.g. the void miner null modifier) belongs to several sets.
 * columns: grid width in the GUI (e.g. 3 for tier rows of energy/speed/item modifiers), 0 = fit the panel.
 * family: sets with the same family may take each other's slots (e.g. all Masterful Machinery ports, since MM layouts
 * use portsAnywhere); the structure then still needs as many blocks of each set as the blueprint has. Empty: the set alone.
 * walls: only positions on one face of the structure's bounds are slots, so a cuboid's frame edges keep their casing
 * (Mekanism refuses glass, ports and valves there).
 */
public record VariantSet(String id, List<String> blocks, String match, int columns, String family, boolean walls) {
    public boolean appliesTo(String entryId) {
        return match.isEmpty() || Catalog.shortName(entryId).contains(match);
    }
}
