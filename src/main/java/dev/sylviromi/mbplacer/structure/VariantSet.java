package dev.sylviromi.mbplacer.structure;

import java.util.List;

/** Blocks that may replace each other in any blueprint. The first one is the default. */
public record VariantSet(String id, List<String> blocks) {
}
