package dev.sylviromi.formwork.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sylviromi.formwork.Formwork;
import dev.sylviromi.formwork.net.Net;
import net.minecraft.client.Minecraft;
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

    private record Fx(BlockPos pos, BlockState state, long start, boolean in) {
        int duration() {
            return in ? Net.GROW_TICKS : SHRINK_TICKS;
        }
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
        for (int i = 0; i < pop.pos().size(); i++)
            ACTIVE.add(new Fx(pop.pos().get(i), Block.stateById(pop.states().get(i)), now, pop.in()));
    }

    /** Eases 0..1: growing decelerates to full size, shrinking accelerates. */
    private static float scale(boolean in, float x) {
        if (!in) return 1 - x * x;
        float y = 1 - Math.min(1, x);
        return 1 - y * y * y;
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ACTIVE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            ACTIVE.clear();
            return;
        }
        float now = mc.level.getGameTime() + event.getPartialTick();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        // a growing block stays drawn until the real one has arrived and been meshed, or it would flash for a frame or two
        ACTIVE.removeIf(f -> now - f.start() >= f.duration() + (f.in() && mc.level.getBlockState(f.pos()).isAir() ? 6 : 0));
        for (Fx f : ACTIVE) {
            float s = Math.max(0, scale(f.in(), (now - f.start()) / f.duration()));
            ps.pushPose();
            ps.translate(f.pos().getX() - cam.x + 0.5, f.pos().getY() - cam.y + 0.5, f.pos().getZ() - cam.z + 0.5);
            ps.scale(s, s, s);
            ps.translate(-0.5, -0.5, -0.5);
            mc.getBlockRenderer().renderBatched(f.state(), f.pos(), mc.level, ps, buffers.getBuffer(net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderType(f.state(), false)),
                    false, mc.level.random, net.minecraftforge.client.model.data.ModelData.EMPTY, null);
            ps.popPose();
        }
        buffers.endBatch();
    }
}
