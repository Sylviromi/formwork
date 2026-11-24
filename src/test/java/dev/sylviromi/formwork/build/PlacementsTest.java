package dev.sylviromi.formwork.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlacementsTest {
    private static Placements.Rec rec(int x0, int x1) {
        return new Placements.Rec(UUID.randomUUID(), new CompoundTag(), BlockPos.ZERO, Direction.NORTH, false, new BoundingBox(x0, 0, 0, x1, 4, 4), null, Set.of(new BlockPos(x0, 1, 1)));
    }

    @Test
    void lookupFindsNewestContainingPlacement() {
        var older = rec(0, 4);
        var newer = rec(3, 8);
        var recs = List.of(older, newer);
        assertSame(older, Placements.at(recs, new BlockPos(1, 2, 2)));
        assertSame(newer, Placements.at(recs, new BlockPos(4, 2, 2)), "overlap: the newest wins");
        assertNull(Placements.at(recs, new BlockPos(9, 2, 2)));
        assertEquals(older, Placements.Rec.load(older.save()), "survives a save/load round trip");
    }

    @Test
    void compassRotationIsClockwiseFromBase() {
        for (Direction base : Direction.Plane.HORIZONTAL)
            for (Direction want : Direction.Plane.HORIZONTAL) {
                Direction f = base;
                for (int i = 0; i < Resolver.turnsTo(base, want); i++) f = f.getClockWise();
                assertEquals(want, f);
            }
    }
}
