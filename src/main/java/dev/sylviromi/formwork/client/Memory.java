package dev.sylviromi.formwork.client;

import dev.sylviromi.formwork.Formwork;
import dev.sylviromi.formwork.structure.Selection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** What the player picked last for each structure, and their favourite structures. Kept in config/formwork_client.dat. */
final class Memory {
    private static final CompoundTag LAST = new CompoundTag();
    private static final Set<String> FAVOURITES = new HashSet<>();
    private static boolean loaded;

    private Memory() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("formwork_client.dat");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(file())) return;
            CompoundTag t = NbtIo.read(file().toFile());
            if (t == null) return;
            LAST.merge(t.getCompound("last"));
            for (Tag s : t.getList("favourites", Tag.TAG_STRING)) FAVOURITES.add(s.getAsString());
        } catch (IOException e) {
            Formwork.LOG.warn("Could not read {}", file(), e);
        }
    }

    private static void save() {
        CompoundTag t = new CompoundTag();
        t.put("last", LAST);
        ListTag list = new ListTag();
        FAVOURITES.forEach(f -> list.add(StringTag.valueOf(f)));
        t.put("favourites", list);
        try {
            NbtIo.write(t, file().toFile());
        } catch (IOException e) {
            Formwork.LOG.warn("Could not write {}", file(), e);
        }
    }

    /** The selection last used for this list entry, or EMPTY. */
    static Selection last(String entryKey) {
        load();
        return LAST.contains(entryKey, Tag.TAG_COMPOUND) ? Selection.load(LAST.getCompound(entryKey)) : Selection.EMPTY;
    }

    static void remember(String entryKey, CompoundTag selection) {
        load();
        if (selection.equals(LAST.get(entryKey))) return;
        LAST.put(entryKey, selection.copy());
        save();
    }

    static boolean favourite(String entryKey) {
        load();
        return FAVOURITES.contains(entryKey);
    }

    static void toggleFavourite(String entryKey) {
        load();
        if (!FAVOURITES.remove(entryKey)) FAVOURITES.add(entryKey);
        save();
    }
}
