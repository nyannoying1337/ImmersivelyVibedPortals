package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Clips entities that are halfway through a portal by the portal plane, on the CPU.
 * <p>
 * In 1.21.1 every entity draw had its own clip plane. In 26.3 entities are submitted as {@link SubmitNode}s,
 * batched by render type and built into one shared vertex buffer later
 * ({@code FeatureRenderDispatcher.prepareFrame}), so a per-entity clip plane on the GPU is not possible.
 * Instead the vertices of a clipped entity are cut by its planes while they are built:
 * <ol>
 *     <li>Extraction: the render state of an entity touching a portal gets the portal's outer clipping plane
 *     (the part that went through is invisible), the projection on the other side gets the inner clipping plane
 *     (the part that has not gone through yet is invisible), see {@link CrossPortalEntityRenderer}.</li>
 *     <li>Submission: while such a render state is submitted, every submit it creates is tagged with its planes,
 *     converted to camera-relative coordinates (the space the vertices are built in).</li>
 *     <li>Building: submits are built in runs of equal planes. Within a clipped run, the vertex builder of
 *     every quad render type is wrapped by a {@link ClippingVertexConsumer}.</li>
 * </ol>
 * Quads (entity models, items, blocks, shadows, flames, name tags) and triangle strips (leashes) are clipped.
 */
@Environment(EnvType.CLIENT)
public class EntityClipping {
    /**
     * World space planes (the part on the positive side stays) per extracted render state.
     * Render states are created per extraction, so they're weakly referenced.
     */
    private static final WeakHashMap<EntityRenderState, Plane[]> statePlanes = new WeakHashMap<>();

    /**
     * Camera-relative plane equations (a, b, c, d per plane) of the submits of clipped entities.
     * Cleared when a level renderer starts submitting.
     */
    private static final Map<SubmitNode, float[]> submitPlanes = new IdentityHashMap<>();

    private static float @Nullable [] currentSubmitPlanes;

    private static float @Nullable [] currentBuildPlanes;

    private static final List<ClippingVertexConsumer> pendingConsumers = new ArrayList<>();

    public static void setStatePlanes(EntityRenderState state, Plane @Nullable [] planes) {
        if (planes == null || planes.length == 0) {
            statePlanes.remove(state);
        }
        else {
            statePlanes.put(state, planes);
        }
    }

    public static void onBeginSubmitFeatures() {
        submitPlanes.clear();
        currentSubmitPlanes = null;
    }

    public static void onBeginSubmitEntity(EntityRenderState state, Vec3 cameraPos) {
        currentSubmitPlanes = null;
        if (statePlanes.isEmpty()) {
            return;
        }
        Plane[] planes = statePlanes.get(state);
        if (planes == null) {
            return;
        }
        float[] equations = new float[planes.length * 4];
        for (int i = 0; i < planes.length; i++) {
            Vec3 n = planes[i].normal();
            // world pos = relative pos + camera pos
            equations[i * 4] = (float) n.x;
            equations[i * 4 + 1] = (float) n.y;
            equations[i * 4 + 2] = (float) n.z;
            equations[i * 4 + 3] = (float) n.dot(cameraPos.subtract(planes[i].pos()));
        }
        currentSubmitPlanes = equations;
    }

    public static void onEndSubmitEntity() {
        currentSubmitPlanes = null;
    }

    public static void onSubmit(SubmitNode submit) {
        if (currentSubmitPlanes != null) {
            submitPlanes.put(submit, currentSubmitPlanes);
        }
    }

    public static boolean hasClippedSubmits() {
        return !submitPlanes.isEmpty();
    }

    public static float @Nullable [] getSubmitPlanes(SubmitNode submit) {
        return submitPlanes.get(submit);
    }

    public static void setCurrentBuildPlanes(float @Nullable [] planes) {
        flushPending();
        currentBuildPlanes = planes;
    }

    /**
     * Called before a feature renderer gets a vertex builder. The shared staged vertex buffer finishes the last
     * builder when another draw is started, so the buffered vertices must be written before that.
     */
    public static void flushPending() {
        if (pendingConsumers.isEmpty()) {
            return;
        }
        for (ClippingVertexConsumer consumer : pendingConsumers) {
            consumer.flush();
        }
        pendingConsumers.clear();
    }

    public static VertexConsumer wrapVertexBuilder(RenderType renderType, VertexConsumer builder) {
        float[] planes = currentBuildPlanes;
        if (planes == null) {
            return builder;
        }
        var topology = renderType.primitiveTopology();
        boolean isStrip = topology == com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLE_STRIP;
        if (topology != com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS && !isStrip) {
            return builder;
        }
        ClippingVertexConsumer consumer = new ClippingVertexConsumer(builder, planes, isStrip);
        pendingConsumers.add(consumer);
        return consumer;
    }

    private static final class Vertex {
        float x, y, z;
        int color;
        float u, v;
        int uv1u, uv1v;
        int uv2u, uv2v;
        float uv3u, uv3v;
        float nx, ny, nz;
        float lineWidth;

        void set(Vertex o) {
            x = o.x; y = o.y; z = o.z;
            color = o.color;
            u = o.u; v = o.v;
            uv1u = o.uv1u; uv1v = o.uv1v;
            uv2u = o.uv2u; uv2v = o.uv2v;
            uv3u = o.uv3u; uv3v = o.uv3v;
            nx = o.nx; ny = o.ny; nz = o.nz;
            lineWidth = o.lineWidth;
        }

        void setLerp(Vertex a, Vertex b, float t) {
            x = lerp(a.x, b.x, t); y = lerp(a.y, b.y, t); z = lerp(a.z, b.z, t);
            color = lerpColor(a.color, b.color, t);
            u = lerp(a.u, b.u, t); v = lerp(a.v, b.v, t);
            uv1u = Math.round(lerp(a.uv1u, b.uv1u, t)); uv1v = Math.round(lerp(a.uv1v, b.uv1v, t));
            uv2u = Math.round(lerp(a.uv2u, b.uv2u, t)); uv2v = Math.round(lerp(a.uv2v, b.uv2v, t));
            uv3u = lerp(a.uv3u, b.uv3u, t); uv3v = lerp(a.uv3v, b.uv3v, t);
            nx = lerp(a.nx, b.nx, t); ny = lerp(a.ny, b.ny, t); nz = lerp(a.nz, b.nz, t);
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) {
                nx /= len; ny /= len; nz /= len;
            }
            lineWidth = lerp(a.lineWidth, b.lineWidth, t);
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private static int lerpColor(int a, int b, float t) {
            int result = 0;
            for (int shift = 0; shift < 32; shift += 8) {
                int ca = (a >>> shift) & 0xFF;
                int cb = (b >>> shift) & 0xFF;
                result |= (Math.round(lerp(ca, cb, t)) & 0xFF) << shift;
            }
            return result;
        }
    }

    /**
     * Quads: buffers each quad, clips it by the planes (Sutherland-Hodgman) and writes the remaining polygon
     * as quads (a triangle becomes a quad with a repeated vertex).
     * Triangle strip (leashes): buffers the whole strip, clips each of its triangles and writes the remaining
     * triangles as one strip, joined by degenerate (zero area) triangles.
     */
    public static final class ClippingVertexConsumer implements VertexConsumer {
        private static final int HAS_COLOR = 1, HAS_UV = 2, HAS_UV1 = 4, HAS_UV2 = 8, HAS_UV3 = 16,
            HAS_NORMAL = 32, HAS_LINE_WIDTH = 64;

        private final VertexConsumer delegate;
        private final float[] planes;
        private final boolean isStrip;

        private final Vertex[] quad = {new Vertex(), new Vertex(), new Vertex(), new Vertex()};
        private int vertexCount = 0;
        private int attributes = 0;

        // the buffered vertices of a triangle strip
        private final List<Vertex> strip = new ArrayList<>();

        // polygon buffers for clipping. A quad clipped by n planes has at most 4 + n vertices
        private Vertex[] polygon = new Vertex[0];
        private Vertex[] clipped = new Vertex[0];

        public ClippingVertexConsumer(VertexConsumer delegate, float[] planes, boolean isStrip) {
            this.delegate = delegate;
            this.planes = planes;
            this.isStrip = isStrip;
        }

        private Vertex current() {
            return isStrip ? strip.getLast() : quad[vertexCount - 1];
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (isStrip) {
                Vertex vertex = new Vertex();
                vertex.x = x;
                vertex.y = y;
                vertex.z = z;
                strip.add(vertex);
                return this;
            }
            if (vertexCount == 4) {
                emitQuad();
            }
            Vertex vertex = quad[vertexCount++];
            vertex.x = x;
            vertex.y = y;
            vertex.z = z;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return setColor((a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF));
        }

        @Override
        public VertexConsumer setColor(int color) {
            current().color = color;
            attributes |= HAS_COLOR;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            current().u = u;
            current().v = v;
            attributes |= HAS_UV;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            current().uv1u = u;
            current().uv1v = v;
            attributes |= HAS_UV1;
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            current().uv2u = u;
            current().uv2v = v;
            attributes |= HAS_UV2;
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            current().uv3u = u;
            current().uv3v = v;
            attributes |= HAS_UV3;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            current().nx = x;
            current().ny = y;
            current().nz = z;
            attributes |= HAS_NORMAL;
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            current().lineWidth = width;
            attributes |= HAS_LINE_WIDTH;
            return this;
        }

        public void flush() {
            if (isStrip) {
                emitStrip();
                return;
            }
            if (vertexCount == 4) {
                emitQuad();
            }
            else {
                // incomplete primitive, should not happen. pass it through
                for (int i = 0; i < vertexCount; i++) {
                    write(quad[i]);
                }
                vertexCount = 0;
            }
        }

        private float distance(Vertex vertex, int plane) {
            int i = plane * 4;
            return planes[i] * vertex.x + planes[i + 1] * vertex.y + planes[i + 2] * vertex.z + planes[i + 3];
        }

        private void emitQuad() {
            vertexCount = 0;
            int planeCount = planes.length / 4;

            boolean allInside = true;
            for (int p = 0; p < planeCount; p++) {
                int inside = 0;
                for (Vertex vertex : quad) {
                    if (distance(vertex, p) >= 0) {
                        inside++;
                    }
                }
                if (inside == 0) {
                    return; // fully clipped
                }
                if (inside != 4) {
                    allInside = false;
                }
            }
            if (allInside) {
                for (Vertex vertex : quad) {
                    write(vertex);
                }
                return;
            }

            int size = clipPolygon(quad, 4);
            if (size < 3) {
                return;
            }

            // triangle fan around vertex 0, two triangles per quad
            for (int i = 1; i + 1 < size; i += 2) {
                write(polygon[0]);
                write(polygon[i]);
                write(polygon[i + 1]);
                write(polygon[Math.min(i + 2, size - 1)]);
            }
        }

        private void emitStrip() {
            if (strip.size() < 3) {
                strip.clear();
                return;
            }
            int planeCount = planes.length / 4;
            boolean allInside = true;
            for (Vertex vertex : strip) {
                for (int p = 0; p < planeCount; p++) {
                    if (distance(vertex, p) < 0) {
                        allInside = false;
                    }
                }
            }
            if (allInside) {
                for (Vertex vertex : strip) {
                    write(vertex);
                }
                strip.clear();
                return;
            }

            // triangle i of the strip is (i, i+1, i+2); the winding alternates, but it doesn't matter
            // for the strips drawn here (leashes are drawn without culling)
            @Nullable Vertex lastWritten = null;
            Vertex[] triangle = new Vertex[3];
            for (int i = 0; i + 2 < strip.size(); i++) {
                triangle[0] = strip.get(i);
                triangle[1] = strip.get(i + 1);
                triangle[2] = strip.get(i + 2);
                int size = clipPolygon(triangle, 3);
                // fan triangles of the clipped polygon
                for (int j = 1; j + 1 < size; j++) {
                    if (lastWritten != null) {
                        // degenerate triangles joining the previous triangle with this one
                        write(lastWritten);
                        write(polygon[0]);
                    }
                    write(polygon[0]);
                    write(polygon[j]);
                    write(polygon[j + 1]);
                    lastWritten = copyOf(polygon[j + 1]);
                }
            }
            strip.clear();
        }

        private static Vertex copyOf(Vertex vertex) {
            Vertex result = new Vertex();
            result.set(vertex);
            return result;
        }

        /**
         * Clips the polygon by all planes. The result is in {@link #polygon}.
         *
         * @return the vertex count of the result
         */
        private int clipPolygon(Vertex[] input, int inputSize) {
            int planeCount = planes.length / 4;
            int capacity = inputSize + planeCount;
            if (polygon.length < capacity) {
                polygon = newVertices(capacity);
                clipped = newVertices(capacity);
            }
            for (int i = 0; i < inputSize; i++) {
                polygon[i].set(input[i]);
            }
            int size = inputSize;
            for (int p = 0; p < planeCount && size > 0; p++) {
                int outSize = 0;
                for (int i = 0; i < size; i++) {
                    Vertex cur = polygon[i];
                    Vertex next = polygon[(i + 1) % size];
                    float dc = distance(cur, p);
                    float dn = distance(next, p);
                    if (dc >= 0) {
                        clipped[outSize++].set(cur);
                    }
                    if ((dc >= 0) != (dn >= 0)) {
                        clipped[outSize++].setLerp(cur, next, dc / (dc - dn));
                    }
                }
                Vertex[] swap = polygon;
                polygon = clipped;
                clipped = swap;
                size = outSize;
            }
            return size;
        }

        private static Vertex[] newVertices(int n) {
            Vertex[] result = new Vertex[n];
            for (int i = 0; i < n; i++) {
                result[i] = new Vertex();
            }
            return result;
        }

        private void write(Vertex vertex) {
            delegate.addVertex(vertex.x, vertex.y, vertex.z);
            int attributes = this.attributes;
            if ((attributes & HAS_COLOR) != 0) delegate.setColor(vertex.color);
            if ((attributes & HAS_UV) != 0) delegate.setUv(vertex.u, vertex.v);
            if ((attributes & HAS_UV1) != 0) delegate.setUv1(vertex.uv1u, vertex.uv1v);
            if ((attributes & HAS_UV2) != 0) delegate.setUv2(vertex.uv2u, vertex.uv2v);
            if ((attributes & HAS_UV3) != 0) delegate.setUv3(vertex.uv3u, vertex.uv3v);
            if ((attributes & HAS_NORMAL) != 0) delegate.setNormal(vertex.nx, vertex.ny, vertex.nz);
            if ((attributes & HAS_LINE_WIDTH) != 0) delegate.setLineWidth(vertex.lineWidth);
        }
    }
}
