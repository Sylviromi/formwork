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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the selected structure as a ghost where it would be placed. The mesh is built once per selection and facing,
 * in coordinates relative to the target, so moving the target only moves the mesh.
 */
@Mod.EventBusSubscriber(modid = MbPlacer.ID, value = Dist.CLIENT)
public final class WorldGhost {
    private static final int MAX_MARKED = 2048;
    private static String meshKey = "";
    private static StructureMesh mesh;
    private static Map<BlockPos, BlockState> rel = Map.of();
    private static AABB relBox;
    private static String markedKey = "";
    private static long markedAt;
    private static List<BlockPos> marked = List.of();

    private WorldGhost() {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ItemStack tool = heldTool();
        Selection sel = PlacerItem.selection(tool);
        PlacerItem.Placement at = tool.isEmpty() || sel.isEmpty() ? null : ClientHooks.currentPlacement(tool);
        if (at == null) return;
        String key = sel.save() + "|" + at.facing() + "|" + System.identityHashCode(Catalog.client) + Catalog.client.blueprints.size();
        if (!key.equals(meshKey)) {
            meshKey = key;
            if (mesh != null) mesh.close();
            mesh = null;
            Map<BlockPos, BlockSpec> specs = Resolver.specs(Catalog.client, sel);
            rel = new LinkedHashMap<>();
            relBox = null;
            if (specs != null && !specs.isEmpty()) Resolver.world(specs, BlockPos.ZERO, at.facing()).forEach((p, b) -> rel.put(p, b.state()));
            for (BlockPos p : rel.keySet()) relBox = relBox == null ? new AABB(p) : relBox.minmax(new AABB(p));
            if (!rel.isEmpty()) mesh = new StructureMesh(rel);
        }
        if (mesh == null) return;
        boolean dismantle = PlacerItem.dismantle(tool), anchored = PlacerItem.anchor(tool) != null;
        BlockPos t = at.target();
        updateMarked(mc, t, dismantle);

        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        float pulse = 0.45f + 0.15f * (float) Math.sin((mc.level.getGameTime() + event.getPartialTick()) / 6.0);
        ps.pushPose();
        ps.translate(t.getX() - cam.x, t.getY() - cam.y, t.getZ() - cam.z);
        if (dismantle) mesh.drawGhost(ps.last().pose(), event.getProjectionMatrix(), 1f, 0.45f, 0.45f, pulse);
        else mesh.drawGhost(ps.last().pose(), event.getProjectionMatrix(), 0.6f, 0.9f, 1f, pulse);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        // place mode: red boxes where something is in the way. dismantle mode: red boxes on blocks that will be removed
        for (BlockPos p : marked) LevelRenderer.renderLineBox(ps, lines, new AABB(p).deflate(0.02), 1f, 0.3f, 0.3f, 1f);
        if (anchored) LevelRenderer.renderLineBox(ps, lines, relBox.inflate(0.01), 1f, 0.8f, 0.25f, 1f);
        else LevelRenderer.renderLineBox(ps, lines, relBox.inflate(0.01), 0.35f, 0.85f, 1f, 1f);
        buffers.endBatch(RenderType.lines());
        ps.popPose();
    }

    /** Re-checks the world every 5 ticks or when the target moves (relative positions). */
    private static void updateMarked(Minecraft mc, BlockPos t, boolean dismantle) {
        String key = meshKey + t + dismantle;
        long now = mc.level.getGameTime();
        if (key.equals(markedKey) && now - markedAt < 5 && now >= markedAt) return;
        markedKey = key;
        markedAt = now;
        List<BlockPos> out = new ArrayList<>();
        for (var e : rel.entrySet()) {
            if (out.size() >= MAX_MARKED) break;
            BlockState world = mc.level.getBlockState(e.getKey().offset(t));
            boolean mark = dismantle ? world.getBlock() == e.getValue().getBlock() : !world.canBeReplaced();
            if (mark) out.add(e.getKey());
        }
        marked = out;
    }

    static ItemStack heldTool() {
        var player = Minecraft.getInstance().player;
        if (player == null) return ItemStack.EMPTY;
        for (InteractionHand hand : InteractionHand.values())
            if (player.getItemInHand(hand).getItem() instanceof PlacerItem) return player.getItemInHand(hand);
        return ItemStack.EMPTY;
    }
}
