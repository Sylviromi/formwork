package dev.sylviromi.formwork.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sylviromi.formwork.Formwork;
import dev.sylviromi.formwork.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/** Blocks that grow in (placing) or shrink away (dismantling) around their centre, drawn on top of the world while they animate. */
@Mod.EventBusSubscriber(modid = Formwork.ID, value = Dist.CLIENT)
public final class BlockFx {
    private static final int SHRINK_TICKS = 8;
    private static final List<Fx> ACTIVE = new ArrayList<>();
    /** Placed blocks that finished growing but are not in the world yet (they land together at the end): drawn as one mesh per batch. */
    private static final List<Held> HELD = new ArrayList<>();

    private static final class Held {
        final BlockPos origin;
        final java.util.Map<BlockPos, BlockState> blocks = new java.util.HashMap<>();
        final long shownAt;

        Held(BlockPos origin, long shownAt) {
            this.origin = origin;
            this.shownAt = shownAt;
        }
    }

    private record Fx(BlockPos pos, BlockState state, long start, boolean in) {
        int duration() {
            return in ? Net.GROW_TICKS : SHRINK_TICKS;
        }
    }

    private record Baked(RenderType type, List<BakedQuad> quads) {
    }

    /** DOWN, UP, NORTH, SOUTH, WEST, EAST: vanilla's face brightness. */
    private static final float[] SHADE = {0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f};
    /** The model quads of each state, read once: the animation only transforms them, instead of meshing every block every frame. */
    private static final java.util.Map<BlockState, List<Baked>> BAKED = new java.util.HashMap<>();

    private static List<Baked> baked(BlockRenderDispatcher dispatcher, BlockState state) {
        return BAKED.computeIfAbsent(state, st -> {
            var model = dispatcher.getBlockModel(st);
            var rand = RandomSource.create(42);
            List<Baked> out = new ArrayList<>();
            for (RenderType type : model.getRenderTypes(st, rand, ModelData.EMPTY)) {
                List<BakedQuad> quads = new ArrayList<>();
                for (Direction d : new Direction[]{null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST})
                    quads.addAll(model.getQuads(st, d, rand, ModelData.EMPTY, type));
                out.add(new Baked(type, quads));
            }
            return out;
        });
    }

    private BlockFx() {
    }

    /** Whether something is shrinking away: the placement ghost stays hidden meanwhile. */
    public static boolean dismantling() {
        return ACTIVE.stream().anyMatch(f -> !f.in());
    }

    /** Whether blocks are still growing in: the placed-structure highlight waits for that. */
    public static boolean growing() {
        return ACTIVE.stream().anyMatch(Fx::in);
    }

    public static void add(Net.Pop pop) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        if (pop.in() && !pop.pos().isEmpty()) {
            Held h = new Held(pop.pos().get(0), now + Net.GROW_TICKS);
            for (int i = 0; i < pop.pos().size(); i++) h.blocks.put(pop.pos().get(i).subtract(h.origin), Block.stateById(pop.states().get(i)));
            HELD.add(h);
        }
        for (int i = 0; i < pop.pos().size(); i++)
            ACTIVE.add(new Fx(pop.pos().get(i), Block.stateById(pop.states().get(i)), now, pop.in()));
    }

    /** Eases 0..1: growing decelerates to full size, shrinking accelerates. */
    private static float scale(boolean in, float x) {
        if (!in) return 1 - x * x;
        float y = 1 - Math.min(1, x);
        return 1 - y * y * y;
    }

    private static void drawBlock(Minecraft mc, PoseStack ps, net.minecraft.client.renderer.MultiBufferSource buffers, Vec3 cam, BlockPos pos, BlockState state, float s) {
        int light = LevelRenderer.getLightColor(mc.level, pos);
        ps.pushPose();
        ps.translate(pos.getX() - cam.x + 0.5, pos.getY() - cam.y + 0.5, pos.getZ() - cam.z + 0.5);
        ps.scale(s, s, s);
        ps.translate(-0.5, -0.5, -0.5);
        for (Baked b : baked(mc.getBlockRenderer(), state)) {
            var vc = buffers.getBuffer(b.type());
            for (BakedQuad q : b.quads()) {
                float shade = q.isShade() ? SHADE[q.getDirection().ordinal()] : 1f;
                int tint = q.isTinted() ? mc.getBlockColors().getColor(state, mc.level, pos, q.getTintIndex()) : -1;
                float r = tint == -1 ? 1 : (tint >> 16 & 255) / 255f, g = tint == -1 ? 1 : (tint >> 8 & 255) / 255f, bl = tint == -1 ? 1 : (tint & 255) / 255f;
                vc.putBulkData(ps.last(), q, r * shade, g * shade, bl * shade, light, OverlayTexture.NO_OVERLAY);
            }
        }
        ps.popPose();
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ACTIVE.isEmpty() && HELD.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            ACTIVE.clear();
            BAKED.clear();
            HELD.clear();
            return;
        }
        float now = mc.level.getGameTime() + event.getPartialTick();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        // a growing block stays drawn until the real one has arrived and been meshed, or it would flash for a frame or two
        ACTIVE.removeIf(f -> now - f.start() >= f.duration());
        HELD.removeIf(h -> {
            return !mc.level.getBlockState(h.origin).isAir() && now - h.shownAt > 6 || now - h.shownAt > 1200;
        });
        for (Held h : HELD) {
            if (now < h.shownAt) continue;
            h.blocks.forEach((rel, state) -> drawBlock(mc, ps, buffers, cam, h.origin.offset(rel), state, 1));
        }
        for (Fx f : ACTIVE) {
            float s = Math.max(0, scale(f.in(), (now - f.start()) / f.duration()));
            if (s <= 0) continue;
            drawBlock(mc, ps, buffers, cam, f.pos(), f.state(), s);
        }
        buffers.endBatch();
    }
}
