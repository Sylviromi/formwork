package dev.sylviromi.mbplacer.structure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class GroupParsingTest {
    @Test
    void tiersAreGroupedByName() {
        assertArrayEquals(new String[]{"examplepack:voidminer/voidminer", "1"}, Catalog.groupOf("examplepack:voidminer/voidminer_tier1_rubetine"));
        assertArrayEquals(new String[]{"examplepack:voidminer/voidminer", "9"}, Catalog.groupOf("examplepack:voidminer/voidminer_tier9_ultimate"));
        assertArrayEquals(new String[]{"examplepack:voidminer/solarpanel", "3"}, Catalog.groupOf("examplepack:voidminer/solarpanel_tier_3_citrinetine"));
        assertArrayEquals(new String[]{"examplepack:crazy_ae2/penrose_sphere", "2"}, Catalog.groupOf("examplepack:crazy_ae2/penrose_sphere_tier_2"));
        assertArrayEquals(new String[]{"examplepack:antimatter_protomolecular_transmutator_mekanism", "1"},
                Catalog.groupOf("examplepack:antimatter_protomolecular_transmutator_mekanism_tier1"));
    }

    @Test
    void namesWithoutTierStayAlone() {
        assertArrayEquals(new String[]{"examplepack:reactor/mekanism_turbine", "0"}, Catalog.groupOf("examplepack:reactor/mekanism_turbine"));
        assertArrayEquals(new String[]{"examplepack:blood_altar_tier_card_3", "0"}, Catalog.groupOf("examplepack:blood_altar_tier_card_3"));
        assertArrayEquals(new String[]{"examplepack:tier5", "0"}, Catalog.groupOf("examplepack:tier5"));
    }
}
