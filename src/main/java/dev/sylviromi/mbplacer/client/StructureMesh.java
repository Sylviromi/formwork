package dev.sylviromi.mbplacer.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tessellates a structure once into one VertexBuffer per chunk layer and draws it from the GPU every frame.
 * Faces between structure blocks are culled because the blocks are tessellated against the structure itself.
 */
final class StructureMesh implements AutoCloseable {
    private static final RenderType[] LAYERS = {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent()};
    private final Map<RenderType, VertexBuffer> vbos = new LinkedHashMap<>();

    StructureMesh(Map<BlockPos, BlockState> blocks) {
        var dispatcher = Minecraft.getInstance().getBlockRenderer();
        var level = new View(blocks);
        RandomSource random = RandomSource.create();
        PoseStack ps = new PoseStack();
        Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        for (RenderType rt : LAYERS) {
            BufferBuilder bb = new BufferBuilder(1 << 16);
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            builders.put(rt, bb);
        }
        blocks.forEach((pos, state) -> {
            var model = dispatcher.getBlockModel(state);
            ModelData data = model.getModelData(level, pos, state, ModelData.EMPTY);
            ps.pushPose();
            ps.translate(pos.getX(), pos.getY(), pos.getZ());
            for (RenderType rt : model.getRenderTypes(state, random, data)) {
                BufferBuilder bb = builders.get(rt);
                if (bb != null) dispatcher.getModelRenderer().tesselateBlock(level, model, state, pos, ps, bb, true, random,
                        state.getSeed(pos), OverlayTexture.NO_OVERLAY, data, rt);
            }
            ps.popPose();
        });
        builders.forEach((rt, bb) -> {
            BufferBuilder.RenderedBuffer rendered = bb.end();
            if (rendered.isEmpty()) {
                rendered.release();
                return;
            }
            VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vbo.bind();
            vbo.upload(rendered);
            VertexBuffer.unbind();
            vbos.put(rt, vbo);
        });
    }

    /** Each layer with its own render state (GUI preview). */
    void draw(Matrix4f modelView, Matrix4f projection) {
        draw(modelView, projection, null, 1, 1, 1, 1);
    }

    /** Every layer drawn translucent with this colour multiplier (world ghost). */
    void drawGhost(Matrix4f modelView, Matrix4f projection, float r, float g, float b, float a) {
        draw(modelView, projection, RenderType.translucent(), r, g, b, a);
    }

    private void draw(Matrix4f modelView, Matrix4f projection, RenderType forced, float r, float g, float b, float a) {
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        vbos.forEach((layer, vbo) -> {
            RenderType shaderType = forced == null ? layer : forced;
            // in the GUI the translucent state would draw into Fabulous' translucent target: use cutout state plus blending
            RenderType rt = forced == null && layer == RenderType.translucent() ? RenderType.cutout() : shaderType;
            rt.setupRenderState();
            if (shaderType == RenderType.translucent()) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
            }
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            RenderSystem.setShaderColor(r, g, b, a);
            vbo.bind();
            vbo.drawWithShader(modelView, projection, shader(shaderType));
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            rt.clearRenderState();
        });
        Minecraft.getInstance().gameRenderer.lightTexture().turnOffLightLayer();
    }

    private static ShaderInstance shader(RenderType rt) {
        if (rt == RenderType.cutoutMipped()) return GameRenderer.getRendertypeCutoutMippedShader();
        if (rt == RenderType.cutout()) return GameRenderer.getRendertypeCutoutShader();
        if (rt == RenderType.translucent()) return GameRenderer.getRendertypeTranslucentShader();
        return GameRenderer.getRendertypeSolidShader();
    }

    @Override
    public void close() {
        vbos.values().forEach(VertexBuffer::close);
        vbos.clear();
    }

    /** The structure as a tiny full-bright level, so models cull hidden faces and connected textures see their neighbours. */
    private record View(Map<BlockPos, BlockState> blocks) implements BlockAndTintGetter {
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public float getShade(Direction d, boolean shade) {
            if (!shade) return 1;
            return switch (d) { case DOWN -> 0.5f; case UP -> 1f; case NORTH, SOUTH -> 0.8f; default -> 0.6f; };
        }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            var level = Minecraft.getInstance().level;
            return level == null ? 0xFFFFFF : resolver.getColor(level.getBiome(pos).value(), pos.getX(), pos.getZ());
        }
        @Override public LevelLightEngine getLightEngine() { return Minecraft.getInstance().level.getLightEngine(); }
        @Override public int getBrightness(LightLayer layer, BlockPos pos) { return 15; }
        @Override public int getRawBrightness(BlockPos pos, int minLight) { return 15; }
        @Override public int getMinBuildHeight() { return -2048; }
        @Override public int getHeight() { return 4096; }
    }
}
