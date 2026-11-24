package dev.sylviromi.formwork.build;

import net.minecraft.core.BlockPos;
import net.minecraftforge.event.TickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Spreads a placement or dismantle over a few ticks (bottom-up or top-down, in the order given) so blocks appear and vanish
 * in a sweep. Everything is checked and paid for before it starts; on server stop whatever is left finishes at once.
 */
public final class Animator {
    private static final int MIN_TICKS = 20, MAX_TICKS = 60;
    private static final List<Job> JOBS = new ArrayList<>();

    private Animator() {
    }

    private static final class Job {
        final List<List<BlockPos>> batches;
        final int lag;
        final Consumer<BlockPos> each;
        final Consumer<List<BlockPos>> effect;
        final BooleanSupplier alive;
        final Runnable done;
        int tick;

        /** One batch per tick, a whole layer (same y) at a time, so the sweep climbs (or falls) at an even pace. */
        Job(List<BlockPos> order, boolean bottomUp, int lag, Consumer<BlockPos> each, Consumer<List<BlockPos>> effect, BooleanSupplier alive, Runnable done) {
            this.lag = lag;
            this.each = each;
            this.effect = effect;
            this.alive = alive;
            this.done = done;
            TreeMap<Integer, List<BlockPos>> layers = new TreeMap<>(bottomUp ? Comparator.<Integer>naturalOrder() : Comparator.<Integer>reverseOrder());
            for (BlockPos p : order) layers.computeIfAbsent(p.getY(), y -> new ArrayList<>()).add(p);
            int ticks = Math.max(MIN_TICKS, Math.min(MAX_TICKS, layers.size() * 2));
            List<List<BlockPos>> out = new ArrayList<>();
            for (int i = 0; i < ticks; i++) out.add(new ArrayList<>());
            int rank = 0;
            for (List<BlockPos> layer : layers.values()) out.get(rank++ * ticks / layers.size()).addAll(layer);
            out.removeIf(List::isEmpty);
            this.batches = out;
        }

        /** Runs this tick's effect batch and the batch that is due lag ticks later (everything left if all); false when finished or abandoned. */
        boolean step(boolean all) {
            if (!alive.getAsBoolean()) return false;
            if (all) {
                for (int i = Math.max(0, tick - lag); i < batches.size(); i++) batches.get(i).forEach(each);
            } else {
                if (tick < batches.size()) effect.accept(batches.get(tick));
                int due = tick - lag;
                if (due >= 0 && due < batches.size()) batches.get(due).forEach(each);
            }
            tick++;
            if (!all && tick - lag < batches.size()) return true;
            done.run();
            return false;
        }
    }

    /**
     * Calls effect with a layer of positions each tick, bottom-up or top-down, and each on the same positions lag ticks later, so the effect (sound,
     * a client-side grow animation) can play before the block changes. The job is dropped without done when alive turns
     * false; done runs after the last position.
     */
    public static void run(List<BlockPos> order, boolean bottomUp, int lag, Consumer<BlockPos> each, Consumer<List<BlockPos>> effect, BooleanSupplier alive, Runnable done) {
        if (order.isEmpty() || !dev.sylviromi.formwork.Config.ANIMATIONS.get()) {
            order.forEach(each);
            done.run();
            return;
        }
        JOBS.add(new Job(order, bottomUp, lag, each, effect, alive, done));
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) return;
        for (Iterator<Job> it = new ArrayList<>(JOBS).iterator(); it.hasNext(); ) {
            Job j = it.next();
            if (!j.step(false)) JOBS.remove(j);
        }
    }

    public static void finishAll(net.minecraftforge.event.server.ServerStoppingEvent event) {
        for (Job j : new ArrayList<>(JOBS)) j.step(true);
        JOBS.clear();
    }
}
