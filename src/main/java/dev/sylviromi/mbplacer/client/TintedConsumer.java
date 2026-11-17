package dev.sylviromi.mbplacer.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Multiplies every vertex colour, used to draw blocks as see-through coloured ghosts. */
final class TintedConsumer implements VertexConsumer {
    private final VertexConsumer d;
    private final float r, g, b, a;

    TintedConsumer(VertexConsumer delegate, float r, float g, float b, float a) {
        this.d = delegate;
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    @Override
    public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v, int overlay, int light, float nx, float ny, float nz) {
        d.vertex(x, y, z, red * r, green * g, blue * b, alpha * a, u, v, overlay, light, nx, ny, nz);
    }

    @Override public VertexConsumer vertex(double x, double y, double z) { d.vertex(x, y, z); return this; }
    @Override public VertexConsumer color(int red, int green, int blue, int alpha) {
        d.color((int) (red * r), (int) (green * g), (int) (blue * b), (int) (alpha * a));
        return this;
    }
    @Override public VertexConsumer uv(float u, float v) { d.uv(u, v); return this; }
    @Override public VertexConsumer overlayCoords(int u, int v) { d.overlayCoords(u, v); return this; }
    @Override public VertexConsumer uv2(int u, int v) { d.uv2(u, v); return this; }
    @Override public VertexConsumer normal(float x, float y, float z) { d.normal(x, y, z); return this; }
    @Override public void endVertex() { d.endVertex(); }
    @Override public void defaultColor(int red, int green, int blue, int alpha) { d.defaultColor(red, green, blue, alpha); }
    @Override public void unsetDefaultColor() { d.unsetDefaultColor(); }
}
