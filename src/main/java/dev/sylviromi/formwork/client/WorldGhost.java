package dev.sylviromi.formwork.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sylviromi.formwork.Formwork;
import dev.sylviromi.formwork.build.Dismantler;
import dev.sylviromi.formwork.build.Placements;
import dev.sylviromi.formwork.build.Resolver;
import dev.sylviromi.formwork.item.PlacerItem;
import dev.sylviromi.formwork.structure.BlockSpec;
import dev.sylviromi.formwork.structure.Catalog;
import dev.sylviromi.formwork.structure.Selection;
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
@Mod.EventBusSubscriber(modid = Formwork.ID, value = Dist.CLIENT)
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
        if (tool.isEmpty()) return;
        Placements.Rec hovered = PlacerItem.placementLookedAt(mc.player);
        // pointing at a placed multiblock: show it instead of a new ghost
        if (hovered != null) {
            renderPlaced(event, mc, hovered);
            return;
        }
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
        boolean anchored = PlacerItem.anchor(tool) != null;
        BlockPos t = at.target();
        updateMarked(mc, t);

        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        float pulse = 0.45f + 0.15f * (float) Math.sin((mc.level.getGameTime() + event.getPartialTick()) / 6.0);
        ps.pushPose();
        ps.translate(t.getX() - cam.x, t.getY() - cam.y, t.getZ() - cam.z);
        mesh.drawGhost(ps.last().pose(), event.getProjectionMatrix(), 0.6f, 0.9f, 1f, pulse);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        // red boxes where something is in the way; grey while Ctrl is held: those spots get skipped
        float mr = ClientHooks.skipBlocked ? 0.6f : 1f, mg = ClientHooks.skipBlocked ? 0.6f : 0.3f, mb = ClientHooks.skipBlocked ? 0.6f : 0.3f;
        for (BlockPos p : marked) LevelRenderer.renderLineBox(ps, lines, new AABB(p).deflate(0.02), mr, mg, mb, 1f);
        if (anchored) LevelRenderer.renderLineBox(ps, lines, relBox.inflate(0.01), 1f, 0.8f, 0.25f, 1f);
        else LevelRenderer.renderLineBox(ps, lines, relBox.inflate(0.01), 0.35f, 0.85f, 1f, 1f);
        buffers.endBatch(RenderType.lines());
        ps.popPose();
    }

    /** Re-checks the world every 5 ticks or when the target moves (relative positions). */
    private static void updateMarked(Minecraft mc, BlockPos t) {
        String key = meshKey + t;
        long now = mc.level.getGameTime();
        if (key.equals(markedKey) && now - markedAt < 5 && now >= markedAt) return;
        markedKey = key;
        markedAt = now;
        List<BlockPos> out = new ArrayList<>();
        for (var e : rel.entrySet()) {
            if (out.size() >= MAX_MARKED) break;
            BlockState world = mc.level.getBlockState(e.getKey().offset(t));
            if (!world.canBeReplaced()) out.add(e.getKey());
        }
        marked = out;
    }

    private static String placedKey = "";
    private static Silhouette placedMesh;

    /**
     * A placed multiblock under the crosshair: a flat-coloured shell over its blocks (red and stronger while the use key is
     * held) and corner brackets on its bounds.
     */
    private static void renderPlaced(RenderLevelStageEvent event, Minecraft mc, Placements.Rec rec) {
        BlockPos t = rec.target();
        String key = rec.id() + "|" + rec.selection();
        if (!key.equals(placedKey)) {
            placedKey = key;
            if (placedMesh != null) placedMesh.close();
            placedMesh = null;
            var blocks = Placements.positions(rec, Catalog.client);
            if (!blocks.isEmpty()) placedMesh = new Silhouette(blocks.stream().map(p -> p.subtract(t)).collect(java.util.stream.Collectors.toSet()));
        }
        AABB box = AABB.of(rec.box()).move(-t.getX(), -t.getY(), -t.getZ());
        float time = (mc.level.getGameTime() + event.getPartialTick()) / 5f;
        float progress = Hud.progress(mc, rec);
        // cyan while pointing, turning red while the use key is held to dismantle
        float r = 0.45f + 0.55f * progress, g = 0.85f - 0.55f * progress, b = 1f - 0.7f * progress;
        float alpha = 0.2f + 0.06f * (float) Math.sin(time) + 0.35f * progress;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        ps.pushPose();
        ps.translate(t.getX() - cam.x, t.getY() - cam.y, t.getZ() - cam.z);
        if (placedMesh != null) placedMesh.draw(ps.last().pose(), event.getProjectionMatrix(), r, g, b, alpha);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        brackets(ps, buffers.getBuffer(RenderType.lines()), box.inflate(0.004), r, g, b);
        buffers.endBatch(RenderType.lines());
        ps.popPose();
    }

    /** Short lines along the three edges at each corner of the box. */
    private static void brackets(PoseStack ps, com.mojang.blaze3d.vertex.VertexConsumer vc, AABB box, float r, float g, float b) {
        double len = Math.min(1, Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) / 4);
        var pose = ps.last();
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? box.minX : box.maxX, y = (i & 2) == 0 ? box.minY : box.maxY, z = (i & 4) == 0 ? box.minZ : box.maxZ;
            double sx = (i & 1) == 0 ? 1 : -1, sy = (i & 2) == 0 ? 1 : -1, sz = (i & 4) == 0 ? 1 : -1;
            line(pose, vc, x, y, z, x + sx * len, y, z, r, g, b);
            line(pose, vc, x, y, z, x, y + sy * len, z, r, g, b);
            line(pose, vc, x, y, z, x, y, z + sz * len, r, g, b);
        }
    }

    private static void line(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer vc, double x1, double y1, double z1, double x2, double y2, double z2,
                             float r, float g, float b) {
        float nx = (float) (x2 - x1), ny = (float) (y2 - y1), nz = (float) (z2 - z1), n = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        vc.vertex(pose.pose(), (float) x1, (float) y1, (float) z1).color(r, g, b, 1f).normal(pose.normal(), nx / n, ny / n, nz / n).endVertex();
        vc.vertex(pose.pose(), (float) x2, (float) y2, (float) z2).color(r, g, b, 1f).normal(pose.normal(), nx / n, ny / n, nz / n).endVertex();
    }

    static ItemStack heldTool() {
        var player = Minecraft.getInstance().player;
        if (player == null) return ItemStack.EMPTY;
        for (InteractionHand hand : InteractionHand.values())
            if (player.getItemInHand(hand).getItem() instanceof PlacerItem) return player.getItemInHand(hand);
        return ItemStack.EMPTY;
    }
}
