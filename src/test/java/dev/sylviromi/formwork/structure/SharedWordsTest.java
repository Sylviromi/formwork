package dev.sylviromi.formwork.structure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedWordsTest {
    private static List<String> words(String s) {
        return List.of(s.split(" "));
    }

    @Test
    void sharedLeadingWords() {
        assertEquals(words("VoidMiner Tier"), Catalog.sharedWords(List.of(words("VoidMiner Tier 1 Rubetine"), words("VoidMiner Tier 9 Ultimate"))));
        assertEquals(words("Fission Reactor"), Catalog.sharedWords(List.of(words("Fission Reactor Glass"), words("Fission Reactor Casing"))));
        assertEquals(List.of(), Catalog.sharedWords(List.of(words("Null Modifier"), words("Rubetine Energy Modifier"))));
    }
}
