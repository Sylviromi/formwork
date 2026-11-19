package dev.sylviromi.formwork.structure.gen;

import dev.sylviromi.formwork.structure.Catalog;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * An integer parameter of a generator. Labels, when present, name each value from min upward (an enum); a label that is a lang key is translated.
 * nameKey: an existing lang key of the owning mod (e.g. a block name) used as the label, so no own translation is needed.
 */
public record Param(String name, int min, int max, int def, List<String> labels, @Nullable String nameKey, boolean auto) {
    public Param(String name, int min, int max, int def, List<String> labels) {
        this(name, min, max, def, labels, null, false);
    }

    /** The value meaning "let the generator decide". */
    public int autoValue() {
        return min - 1;
    }

    /** Adds an Auto choice (below min) and makes it the default. */
    public Param withAuto() {
        return new Param(name, min, max, min - 1, labels, nameKey, true);
    }

    public boolean isAuto(int value) {
        return auto && value == autoValue();
    }

    public static Param range(String name, int min, int max, int def) {
        return new Param(name, min, max, def, List.of());
    }

    public static Param toggle(String name, boolean def) {
        return new Param(name, 0, 1, def ? 1 : 0, List.of("options.off", "options.on"));
    }

    public Param named(String langKey) {
        return new Param(name, min, max, def, labels, langKey, auto);
    }

    public int clamp(int v) {
        return Math.max(auto ? min - 1 : min, Math.min(max, v));
    }

    /** The owning mod's name for it, else ours (formwork.param.*), else the prettified name. */
    public Component label() {
        Language lang = Language.getInstance();
        if (nameKey != null && lang.has(nameKey)) return Component.translatable(nameKey);
        String own = "formwork.param." + name;
        return lang.has(own) ? Component.translatable(own) : Component.literal(Catalog.prettify(name));
    }

    public Component valueLabel(int value) {
        if (isAuto(value)) return Component.translatable("formwork.gui.auto");
        if (labels.isEmpty()) return Component.literal(String.valueOf(value));
        String l = labels.get(clamp(value) - min);
        if (Language.getInstance().has(l)) return Component.translatable(l);
        // a borrowed key its mod no longer has: its last part, readable ("…basic_evaporation_plant" -> "Basic Evaporation Plant")
        return Component.literal(l.contains(".") ? Catalog.prettify(l.substring(l.lastIndexOf('.') + 1)) : l);
    }
}
