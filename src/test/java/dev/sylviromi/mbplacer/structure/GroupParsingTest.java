package dev.sylviromi.mbplacer.structure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class GroupParsingTest {
    @Test
    void tiersAreGroupedByName() {
        assertArrayEquals(new String[]{"mbtool:voidminer/voidminer", "1"}, Catalog.groupOf("mbtool:voidminer/voidminer_tier1_rubetine"));
        assertArrayEquals(new String[]{"mbtool:voidminer/voidminer", "9"}, Catalog.groupOf("mbtool:voidminer/voidminer_tier9_ultimate"));
        assertArrayEquals(new String[]{"mbtool:voidminer/solarpanel", "3"}, Catalog.groupOf("mbtool:voidminer/solarpanel_tier_3_citrinetine"));
        assertArrayEquals(new String[]{"mbtool:crazy_ae2/penrose_sphere", "2"}, Catalog.groupOf("mbtool:crazy_ae2/penrose_sphere_tier_2"));
        assertArrayEquals(new String[]{"mbtool:antimatter_protomolecular_transmutator_mekanism", "1"},
                Catalog.groupOf("mbtool:antimatter_protomolecular_transmutator_mekanism_tier1"));
    }

    @Test
    void namesWithoutTierStayAlone() {
        assertArrayEquals(new String[]{"mbtool:reactor/mekanism_turbine", "0"}, Catalog.groupOf("mbtool:reactor/mekanism_turbine"));
        assertArrayEquals(new String[]{"mbtool:blood_altar_tier_card_3", "0"}, Catalog.groupOf("mbtool:blood_altar_tier_card_3"));
        assertArrayEquals(new String[]{"mbtool:tier5", "0"}, Catalog.groupOf("mbtool:tier5"));
    }
}
