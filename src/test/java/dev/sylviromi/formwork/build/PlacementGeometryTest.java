package dev.sylviromi.formwork.build;

import dev.sylviromi.formwork.structure.BlockSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacementGeometryTest {
    /** A 3 wide, 2 high, 4 deep box of plain specs. */
    private static Map<BlockPos, BlockSpec> box() {
        Map<BlockPos, BlockSpec> m = new LinkedHashMap<>();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 4; z++) m.put(new BlockPos(x, y, z), BlockSpec.of("minecraft:stone"));
        return m;
    }

    private static int[] bounds(Map<BlockPos, ?> world) {
        var f = Resolver.Footprint.of(world.keySet());
        return new int[]{f.minX(), f.minY(), f.minZ(), f.maxX(), f.maxY(), f.maxZ()};
    }

    private static Map<BlockPos, Boolean> layout(BlockPos target, Direction facing) {
        var at = Resolver.layout(Resolver.Footprint.of(box().keySet()), target, facing);
        Map<BlockPos, Boolean> out = new LinkedHashMap<>();
        box().keySet().forEach(p -> out.put(at.apply(p), true));
        return out;
    }

    @Test
    void nearFaceStartsAtTheAimedBlock() {
        var fp = Resolver.Footprint.of(box().keySet());
        BlockPos front = new BlockPos(10, 64, 10);
        int[] s = bounds(layout(Resolver.centerFor(fp, front, Direction.SOUTH, Direction.SOUTH), Direction.SOUTH));
        assertEquals(10, s[2], "facing south: the structure starts at the aimed block");
        assertEquals(13, s[5], "and is 4 deep");
        int[] n = bounds(layout(Resolver.centerFor(fp, front, Direction.NORTH, Direction.NORTH), Direction.NORTH));
        assertEquals(10, n[5]);
        int[] e = bounds(layout(Resolver.centerFor(fp, front, Direction.EAST, Direction.EAST), Direction.EAST));
        assertEquals(10, e[0]);
        assertEquals(64, e[1], "bottom on the aimed block's level");
    }

    @Test
    void turnIsIndependentOfTheSideItExtendsTo() {
        var fp = Resolver.Footprint.of(box().keySet());
        BlockPos front = new BlockPos(10, 64, 10);
        // turned east (4 deep along x, 3 wide along z) but placed extending south from the aimed block
        int[] b = bounds(layout(Resolver.centerFor(fp, front, Direction.SOUTH, Direction.EAST), Direction.EAST));
        assertEquals(10, b[2], "near face on the aimed block");
        assertEquals(3, b[5] - b[2] + 1, "3 wide along z when turned east");
    }

    @Test
    void turningKeepsTheCentre() {
        BlockPos c = new BlockPos(0, 64, 0);
        int[] s = bounds(layout(c, Direction.SOUTH)), e = bounds(layout(c, Direction.EAST));
        assertEquals((s[0] + s[3]) / 2, (e[0] + e[3]) / 2);
        assertEquals((s[2] + s[5]) / 2, (e[2] + e[5]) / 2);
    }
}
