package dev.sylviromi.mbplacer.structure.gen;

import dev.sylviromi.mbplacer.structure.BlockSpec;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mekanism industrial turbine. Square, odd sized. Rotor shaft in the centre, rotational complex on top of it
 * surrounded by a full layer of pressure dispersers, coils touching the complex above, vents above the dispersers.
 * shortcut: rotors are placed without blades; the player still inserts blades by hand.
 */
public final class IndustrialTurbine implements Generator {
    private static final String M = "mekanism:", G = "mekanismgenerators:";

    @Override public String id() { return "mbplacer:gen/mekanism_turbine"; }
    @Override public String requiredMod() { return "mekanismgenerators"; }

    @Override
    public List<Param> params() {
        return List.of(new Param("size", 0, 6, 0, List.of("5", "7", "9", "11", "13", "15", "17")),
                Param.range("height", 5, 18, 9), Param.range("rotors", 1, 14, 3), Param.range("coils", 1, 24, 2),
                Param.toggle("glass", true), Param.toggle("condensers", false), Param.range("valves", 1, 8, 2));
    }

    @Override
    public Map<BlockPos, BlockSpec> build(int[] v) {
        int s = 5 + 2 * v[0], h = v[1], c = s / 2;
        int rotors = Math.max(1, Math.min(v[2], Math.min(h - 4, 2 * s - 1)));
        int disperserY = rotors + 1;
        Box box = new Box(s, h, s).shell(G + "turbine_casing", v[4] == 1 ? M + "structural_glass" : G + "turbine_casing");
        for (int y = 1; y <= rotors; y++) box.set(c, y, c, G + "turbine_rotor");
        box.layer(disperserY, M + "pressure_disperser");
        box.set(c, disperserY, c, G + "rotational_complex");
        // coils spread outward from the centre column in rings, starting on the layer above the complex
        int coils = Math.min(v[3], (s - 2) * (s - 2) * (h - 2 - disperserY - 1));
        for (int y = disperserY + 1; y < h - 1 && coils > 0; y++)
            for (int r = 0; r <= c && coils > 0; r++)
                for (int x = c - r; x <= c + r && coils > 0; x++)
                    for (int z = c - r; z <= c + r && coils > 0; z++)
                        if (Math.max(Math.abs(x - c), Math.abs(z - c)) == r && x > 0 && z > 0 && x < s - 1 && z < s - 1) {
                            box.set(x, y, z, G + "electromagnetic_coil");
                            coils--;
                        }
        if (v[5] == 1)
            for (int y = disperserY + 1; y < h - 1; y++)
                for (int x = 1; x < s - 1; x++)
                    for (int z = 1; z < s - 1; z++)
                        box.blocks.putIfAbsent(new BlockPos(x, y, z), BlockSpec.of(G + "saturating_condenser"));
        // vents: the whole ceiling inside the frame
        for (int x = 1; x < s - 1; x++)
            for (int z = 1; z < s - 1; z++) box.set(x, h - 1, z, G + "turbine_vent");
        List<BlockPos> walls = new ArrayList<>(box.sideCentres(1));
        if (rotors > 1) walls.addAll(box.sideCentres(2));
        box.onWalls(walls, v[6], G + "turbine_valve");
        return box.blocks;
    }
}
