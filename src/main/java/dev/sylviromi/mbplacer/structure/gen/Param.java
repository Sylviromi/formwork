package dev.sylviromi.mbplacer.structure.gen;

import java.util.List;

/** An integer parameter of a generator. Labels, when present, name each value from min upward (an enum). */
public record Param(String name, int min, int max, int def, List<String> labels) {
    public static Param range(String name, int min, int max, int def) {
        return new Param(name, min, max, def, List.of());
    }

    public static Param toggle(String name, boolean def) {
        return new Param(name, 0, 1, def ? 1 : 0, List.of("off", "on"));
    }

    public int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }
}
