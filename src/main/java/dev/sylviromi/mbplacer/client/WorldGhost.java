package dev.sylviromi.mbplacer.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sylviromi.mbplacer.MbPlacer;
import dev.sylviromi.mbplacer.build.Resolver;
import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.structure.BlockSpec;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/**
 * Draws the selected structure as a ghost where it would be placed.
 * shortcut: re-tessellates every block each frame; cache in VertexBuffers if large structures cost too many fps.
 */
@Mod.EventBusSubscriber(modid = MbPlacer.ID, value = Dist.CLIENT)
public final class WorldGhost {
    private static String cacheKey = "";
    private static Map<BlockPos, Resolver.Placed> cache = Map.of();

    private WorldGhost() {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        ItemStack tool = heldTool();
        if (tool.isEmpty()) return;
        Selection sel = PlacerItem.selection(tool);
        if (sel.isEmpty()) return;
        BlockPos target = PlacerItem.target(mc.level, hit);
        Direction facing = mc.player.getDirection();
        String key = sel.save() + "|" + target + "|" + facing + "|" + System.identityHashCode(Catalog.client) + Catalog.client.blueprints.size();
        if (!key.equals(cacheKey)) {
            cacheKey = key;
            Map<BlockPos, BlockSpec> specs = Resolver.specs(Catalog.client, sel);
            cache = specs == null || specs.isEmpty() ? Map.of() : Resolver.world(specs, target, facing);
        }
        if (cache.isEmpty()) return;

        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher br = mc.getBlockRenderer();
        float pulse = 0.45f + 0.15f * (float) Math.sin((mc.level.getGameTime() + event.getPartialTick()) / 6.0);
        AABB box = null;
        for (var e : cache.entrySet()) {
            BlockPos pos = e.getKey();
            boolean blocked = !mc.level.getBlockState(pos).canBeReplaced();
            TintedConsumer vc = blocked ? new TintedConsumer(buffers.getBuffer(RenderType.translucent()), 1f, 0.3f, 0.3f, 0.8f)
                    : new TintedConsumer(buffers.getBuffer(RenderType.translucent()), 0.6f, 0.9f, 1f, pulse);
            ps.pushPose();
            ps.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
            br.renderSingleBlock(e.getValue().state(), ps, rt -> vc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            ps.popPose();
            box = box == null ? new AABB(pos) : box.minmax(new AABB(pos));
        }
        buffers.endBatch(RenderType.translucent());
        if (box != null) {
            ps.pushPose();
            ps.translate(-cam.x, -cam.y, -cam.z);
            LevelRenderer.renderLineBox(ps, buffers.getBuffer(RenderType.lines()), box.inflate(0.01), 0.35f, 0.85f, 1f, 1f);
            ps.popPose();
            buffers.endBatch(RenderType.lines());
        }
    }

    static ItemStack heldTool() {
        var player = Minecraft.getInstance().player;
        if (player == null) return ItemStack.EMPTY;
        for (InteractionHand hand : InteractionHand.values())
            if (player.getItemInHand(hand).getItem() instanceof PlacerItem) return player.getItemInHand(hand);
        return ItemStack.EMPTY;
    }
}
