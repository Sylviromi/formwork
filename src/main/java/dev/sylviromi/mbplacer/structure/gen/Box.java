package dev.sylviromi.mbplacer.structure.gen;

import dev.sylviromi.mbplacer.structure.BlockSpec;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A hollow cuboid w*h*l, the shape almost every Mekanism multiblock shares. */
final class Box {
    final int w, h, l;
    final Map<BlockPos, BlockSpec> blocks = new LinkedHashMap<>();

    Box(int w, int h, int l) {
        this.w = w;
        this.h = h;
        this.l = l;
    }

    private int sides(int x, int y, int z) {
        return (x == 0 || x == w - 1 ? 1 : 0) + (y == 0 || y == h - 1 ? 1 : 0) + (z == 0 || z == l - 1 ? 1 : 0);
    }

    /** Frame on edges, face on the walls/floor/ceiling. */
    Box shell(String frame, String face) {
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                for (int z = 0; z < l; z++) {
                    int s = sides(x, y, z);
                    if (s >= 2) blocks.put(new BlockPos(x, y, z), BlockSpec.of(frame));
                    else if (s == 1) blocks.put(new BlockPos(x, y, z), BlockSpec.of(face));
                }
        return this;
    }

    void set(int x, int y, int z, String id) {
        blocks.put(new BlockPos(x, y, z), BlockSpec.of(id));
    }

    /** Fills a whole interior layer. */
    void layer(int y, String id) {
        for (int x = 1; x < w - 1; x++)
            for (int z = 1; z < l - 1; z++) set(x, y, z, id);
    }

    /** Wall (not frame) positions at the centre of each of the four sides at height y. */
    List<BlockPos> sideCentres(int y) {
        List<BlockPos> out = new ArrayList<>();
        out.add(new BlockPos(w / 2, y, 0));
        out.add(new BlockPos(w / 2, y, l - 1));
        out.add(new BlockPos(0, y, l / 2));
        out.add(new BlockPos(w - 1, y, l / 2));
        return out;
    }

    /** Puts `count` blocks of id on the given candidate wall positions, in order. */
    void onWalls(List<BlockPos> candidates, int count, String id) {
        for (BlockPos p : candidates) {
            if (count <= 0) return;
            if (sides(p.getX(), p.getY(), p.getZ()) != 1 || isAny(p, "port")) continue;
            blocks.put(p, BlockSpec.of(id));
            count--;
        }
    }

    private boolean isAny(BlockPos p, String part) {
        BlockSpec s = blocks.get(p);
        return s != null && (s.id().contains(part) || s.id().contains("valve") || s.id().contains("adapter") || s.id().contains("controller"));
    }
}
