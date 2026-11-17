package dev.sylviromi.mbplacer.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

import java.util.Set;

/**
 * The outer faces of a set of blocks as one flat-coloured shell, pushed a hair outwards. Drawn translucent over a placed
 * multiblock as its highlight: unlike tinting the block textures it shows equally well on dark and light blocks.
 */
final class Silhouette implements AutoCloseable {
    private static final float OUT = 0.004f;
    private final VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);

    Silhouette(Set<BlockPos> blocks) {
        BufferBuilder bb = new BufferBuilder(1 << 14);
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (BlockPos p : blocks)
            for (Direction d : Direction.values())
                if (!blocks.contains(p.relative(d))) face(bb, p, d);
        vbo.bind();
        vbo.upload(bb.end());
        VertexBuffer.unbind();
    }

    /** One block face as a white quad; the colour comes from the shader colour when drawn. */
    private static void face(BufferBuilder bb, BlockPos p, Direction d) {
        float x0 = p.getX() - OUT, y0 = p.getY() - OUT, z0 = p.getZ() - OUT, x1 = p.getX() + 1 + OUT, y1 = p.getY() + 1 + OUT, z1 = p.getZ() + 1 + OUT;
        float[][] q = switch (d) {
            case DOWN -> new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
            case UP -> new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
            case NORTH -> new float[][]{{x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}};
            case SOUTH -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            case WEST -> new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
            case EAST -> new float[][]{{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}};
        };
        for (float[] v : q) bb.vertex(v[0], v[1], v[2]).color(1f, 1f, 1f, 1f).endVertex();
    }

    void draw(Matrix4f modelView, Matrix4f projection, float r, float g, float b, float a) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(r, g, b, a);
        vbo.bind();
        vbo.drawWithShader(modelView, projection, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    @Override
    public void close() {
        vbo.close();
    }
}
