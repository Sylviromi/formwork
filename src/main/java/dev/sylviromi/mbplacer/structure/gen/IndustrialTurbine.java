package dev.sylviromi.mbplacer.structure.gen;

import dev.sylviromi.mbplacer.structure.BlockSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mekanism industrial turbine. Square, odd sized. Rotor shaft in the centre, rotational complex on top of it
 * surrounded by a full layer of pressure dispersers, coils touching the complex above, vents above the dispersers.
 * Rotors come with both blades in (Mekanism reads "blades" from each rotor when the turbine forms).
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
        int rotors = Math.max(1, Math.min(v[2], Math.min(h - 4, 4 * ((s - 3) / 2) + 3))); // TurbineValidator: (s-3)/2 >= rotors/4
        int disperserY = rotors + 1;
        Box box = new Box(s, h, s).shell(G + "turbine_casing", v[4] == 1 ? M + "structural_glass" : G + "turbine_casing");
        for (int y = 1; y <= rotors; y++) {
            CompoundTag nbt = new CompoundTag();
            nbt.putInt("blades", 2);
            nbt.putInt("position", y - 1);
            box.blocks.put(new BlockPos(c, y, c), new BlockSpec(G + "turbine_rotor", Map.of(), nbt));
        }
        box.layer(disperserY, M + "pressure_disperser");
        box.set(c, disperserY, c, G + "rotational_complex");
        // coils must form one face-connected group starting above the complex: fill by Manhattan distance, layer by layer
        int coils = v[3];
        for (int y = disperserY + 1; y < h - 1 && coils > 0; y++)
            for (int d = 0; d <= 2 * (c - 1) && coils > 0; d++)
                for (int x = 1; x < s - 1 && coils > 0; x++)
                    for (int z = 1; z < s - 1 && coils > 0; z++)
                        if (Math.abs(x - c) + Math.abs(z - c) == d) {
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
