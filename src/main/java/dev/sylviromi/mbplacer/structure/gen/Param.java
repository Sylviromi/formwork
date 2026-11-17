package dev.sylviromi.mbplacer.structure.gen;

import dev.sylviromi.mbplacer.structure.Catalog;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * An integer parameter of a generator. Labels, when present, name each value from min upward (an enum); a label that is a lang key is translated.
 * nameKey: an existing lang key of the owning mod (e.g. a block name) used as the label, so no own translation is needed.
 */
public record Param(String name, int min, int max, int def, List<String> labels, @Nullable String nameKey) {
    public Param(String name, int min, int max, int def, List<String> labels) {
        this(name, min, max, def, labels, null);
    }

    public static Param range(String name, int min, int max, int def) {
        return new Param(name, min, max, def, List.of());
    }

    public static Param toggle(String name, boolean def) {
        return new Param(name, 0, 1, def ? 1 : 0, List.of("options.off", "options.on"));
    }

    public Param named(String langKey) {
        return new Param(name, min, max, def, labels, langKey);
    }

    public int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }

    /** The owning mod's name for it, else ours (mbplacer.param.*), else the prettified name. */
    public Component label() {
        Language lang = Language.getInstance();
        if (nameKey != null && lang.has(nameKey)) return Component.translatable(nameKey);
        String own = "mbplacer.param." + name;
        return lang.has(own) ? Component.translatable(own) : Component.literal(Catalog.prettify(name));
    }

    public Component valueLabel(int value) {
        if (labels.isEmpty()) return Component.literal(String.valueOf(value));
        String l = labels.get(clamp(value) - min);
        return Language.getInstance().has(l) ? Component.translatable(l) : Component.literal(l);
    }
}
