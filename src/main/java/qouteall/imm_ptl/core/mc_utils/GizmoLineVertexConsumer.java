package qouteall.imm_ptl.core.mc_utils;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * In 26.3, MultiBufferSource and the immediate line render types used by the portal wand rendering were removed.
 * This adapts the old VertexConsumer-based line rendering code
 * (that emits camera-relative line vertices) to the 26.3 {@link Gizmos} system.
 * It must be used while a gizmo collector is active
 * (e.g. inside {@link net.minecraft.client.renderer.debug.DebugRenderer#emitGizmos}).
 * Call {@link #finish()} after emitting all vertices.
 * Only position, color and line width are used. Per-vertex colors are not supported:
 * a line segment uses the color of its first vertex.
 */
@Environment(EnvType.CLIENT)
public class GizmoLineVertexConsumer implements VertexConsumer {
    public static final float DEFAULT_LINE_WIDTH = 2.5f;

    private final Vec3 cameraPos;
    private final boolean isLineStrip;
    private final float defaultLineWidth;

    private boolean hasPending = false;
    private float pendingX, pendingY, pendingZ;
    private int pendingColor = 0xFFFFFFFF;
    private float pendingLineWidth;

    @Nullable
    private Vec3 lastPos = null;
    private int lastColor;
    private float lastLineWidth;

    private GizmoLineVertexConsumer(Vec3 cameraPos, boolean isLineStrip, float defaultLineWidth) {
        this.cameraPos = cameraPos;
        this.isLineStrip = isLineStrip;
        this.defaultLineWidth = defaultLineWidth;
    }

    /**
     * Replacement of the old RenderType.lines() buffer: every two vertices form a line.
     */
    public static GizmoLineVertexConsumer lines(Vec3 cameraPos) {
        return new GizmoLineVertexConsumer(cameraPos, false, DEFAULT_LINE_WIDTH);
    }

    /**
     * Replacement of the old RenderType.debugLineStrip(width) buffer: every vertex connects to the previous one.
     */
    public static GizmoLineVertexConsumer lineStrip(Vec3 cameraPos, float lineWidth) {
        return new GizmoLineVertexConsumer(cameraPos, true, lineWidth);
    }

    private void flushPending() {
        if (!hasPending) {
            return;
        }
        hasPending = false;

        Vec3 pos = new Vec3(
            pendingX + cameraPos.x, pendingY + cameraPos.y, pendingZ + cameraPos.z
        );

        if (lastPos == null) {
            lastPos = pos;
            lastColor = pendingColor;
            lastLineWidth = pendingLineWidth;
            return;
        }

        // skip the invisible (alpha 0) and zero-length segments
        // (the line strip code uses alpha 0 vertices to "jump")
        if (((lastColor >>> 24) & 0xFF) != 0 && !lastPos.equals(pos)) {
            Gizmos.line(lastPos, pos, lastColor, lastLineWidth);
        }

        if (isLineStrip) {
            lastPos = pos;
            lastColor = pendingColor;
            lastLineWidth = pendingLineWidth;
        }
        else {
            lastPos = null;
        }
    }

    /**
     * Emit the remaining vertices. For line strip, it also ends the current strip.
     */
    public void finish() {
        flushPending();
        lastPos = null;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        flushPending();
        hasPending = true;
        pendingX = x;
        pendingY = y;
        pendingZ = z;
        pendingColor = 0xFFFFFFFF;
        pendingLineWidth = defaultLineWidth;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        pendingColor = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        pendingColor = color;
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        pendingLineWidth = width;
        return this;
    }
}
