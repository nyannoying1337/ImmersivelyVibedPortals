package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Cropped portal views: a portal view is rendered only for the screen rectangle that its portal covers, into a
 * target of that size, at the window's pixel density (so it looks the same, a portal covering 2% of the screen
 * costs about 2% of the pixels). See docs/rendering-26.3.md.
 * <p>
 * "Screen space" here is the NDC of the window for a view's camera: what the view would show if it was rendered
 * full-size and not mirrored. It's the same for a view and the views behind its portals (where the portal is on
 * the screen, the view behind it shows what's behind it). A view's {@link Mapping} maps screen space to its target's
 * NDC: the crop, and the horizontal flip of mirrored views. Rectangles are snapped to the window's pixel grid, so a
 * view's pixels line up with the screen's.
 * <p>
 * Not with a shaderpack (Iris' buffers are window-sized), in panorama screenshots, and while the nausea/portal
 * screen effect distorts the projection (its skew is not computed here).
 */
public final class PortalViewCrop {
    private static final Minecraft client = Minecraft.getInstance();

    // pixels added around a portal's rectangle (rasterization at the edges)
    private static final int MARGIN = 2;
    // a view covering more than this part of its parent's view is rendered full-size
    private static final float FULL_SIZE_AREA_RATIO = 0.6F;

    /**
     * An affine map, per axis: target NDC = s * screen NDC + t.
     */
    public record Mapping(float sx, float tx, float sy, float ty) {
        public static final Mapping IDENTITY = new Mapping(1, 0, 1, 0);
        public static final Mapping FLIPPED = new Mapping(-1, 0, 1, 0);

        public static Mapping fullSize(boolean mirrored) {
            return mirrored ? FLIPPED : IDENTITY;
        }

        /**
         * The view renders the screen space rectangle [x0, x1] x [y0, y1] (screen NDC) into its whole target.
         */
        public static Mapping ofRect(float x0, float x1, float y0, float y1, boolean mirrored) {
            float f = mirrored ? -1 : 1;
            return new Mapping(
                f * 2 / (x1 - x0), -f * (x0 + x1) / (x1 - x0),
                2 / (y1 - y0), -(y0 + y1) / (y1 - y0)
            );
        }

        /**
         * Applied to the view's projection (in clip space).
         */
        public Matrix4f toClipMatrix() {
            // JOML's constructor takes columns
            return new Matrix4f(
                sx, 0, 0, 0,
                0, sy, 0, 0,
                0, 0, 1, 0,
                tx, ty, 0, 1
            );
        }

        public boolean isIdentity() {
            return sx == 1 && tx == 0 && sy == 1 && ty == 0;
        }

        // the part of screen space that the view shows
        float screenMinX() {
            return Math.min((-1 - tx) / sx, (1 - tx) / sx);
        }

        float screenMaxX() {
            return Math.max((-1 - tx) / sx, (1 - tx) / sx);
        }

        float screenMinY() {
            return Math.min((-1 - ty) / sy, (1 - ty) / sy);
        }

        float screenMaxY() {
            return Math.max((-1 - ty) / sy, (1 - ty) / sy);
        }
    }

    /**
     * The texture matrix for sampling a child view's target while drawing its portal in the parent view:
     * parent target uv (homogeneous, from {@code projection_from_position}) to child target uv.
     */
    public static Matrix4f samplingMatrix(Mapping parent, Mapping child) {
        // child NDC = k * parent NDC + m, per axis; uv = (ndc + 1) / 2
        float kx = child.sx / parent.sx;
        float mx = child.tx - kx * parent.tx;
        float ky = child.sy / parent.sy;
        float my = child.ty - ky * parent.ty;
        return new Matrix4f(
            kx, 0, 0, 0,
            0, ky, 0, 0,
            0, 0, 1, 0,
            (mx + 1 - kx) / 2, (my + 1 - ky) / 2, 0, 1
        );
    }

    /**
     * A view's size and mapping, decided before its target is acquired.
     */
    public static final class Crop {
        // the rectangle in window pixels (x from the left, y from the bottom)
        public int x0;
        public int y0;
        public int width;
        public int height;
        public boolean fullSize;

        /**
         * After the target is acquired (it may be larger than asked): the rectangle grows to the target's size,
         * keeping its pixels aligned with the window's and staying inside the window where possible.
         */
        public Mapping finish(int targetWidth, int targetHeight, boolean mirrored) {
            int windowWidth = client.getWindow().getWidth();
            int windowHeight = client.getWindow().getHeight();
            if (fullSize || (targetWidth == windowWidth && targetHeight == windowHeight && x0 == 0 && y0 == 0)) {
                return Mapping.fullSize(mirrored);
            }
            int x = Math.max(0, Math.min(x0, windowWidth - targetWidth));
            int y = Math.max(0, Math.min(y0, windowHeight - targetHeight));
            return Mapping.ofRect(
                (float) x / windowWidth * 2 - 1, (float) (x + targetWidth) / windowWidth * 2 - 1,
                (float) y / windowHeight * 2 - 1, (float) (y + targetHeight) / windowHeight * 2 - 1,
                mirrored
            );
        }
    }

    // per frame
    private static boolean enabledThisFrame = false;
    // the main camera's render state of this frame
    private static final CameraRenderState scratchCameraState = new CameraRenderState();

    public static boolean isEnabledThisFrame() {
        return enabledThisFrame;
    }

    /**
     * Called before portal views are rendered in a frame.
     */
    public static void beginFrame(Camera mainCamera, DeltaTracker deltaTracker) {
        LocalPlayer player = client.player;
        enabledThisFrame = IPCGlobal.cropPortalViews
            && player != null
            && !IrisInterface.invoker.isShaderpackInUse()
            && !mainCamera.isPanoramicMode()
            // the screen effect's skew (GameRenderer.renderLevel)
            && player.portalEffectIntensity <= 0 && player.getEffectBlendFactor(MobEffects.NAUSEA, 1.0F) <= 0;
        if (!enabledThisFrame) {
            return;
        }
        mainCamera.extractRenderState(scratchCameraState, deltaTracker);
    }

    /**
     * The matrix from camera-relative world positions to screen space clip coordinates of the current view
     * (whose camera is the game renderer's main camera now). Null if views are not cropped this frame.
     */
    public static @Nullable Matrix4f getScreenClipMatrix() {
        if (!enabledThisFrame) {
            return null;
        }
        // As GameRenderer.renderLevel computes the projection: the extracted one (all views have the same in x and y,
        // which don't depend on the near plane; isometric view replaces it) with the view bobbing and damage tilt
        // (all views bob like the player's camera; the bobbing offset depends on the view, see
        // RenderStates.getViewBobbingOffsetMultiplier).
        PoseStack bobStack = new PoseStack();
        ((IEGameRenderer) client.gameRenderer).ip_applyViewBobbing(scratchCameraState, bobStack);
        Camera camera = client.gameRenderer.mainCamera();
        return new Matrix4f(scratchCameraState.projectionMatrix)
            .mul(bobStack.last().pose())
            .mul(camera.getViewRotationMatrix(new Matrix4f()));
    }

    /**
     * The crop of the view behind the portal, seen from the current view.
     *
     * @param screenClipMatrix see {@link #getScreenClipMatrix()}, null for a full-size view
     * @param parent           the mapping of the current view
     * @return null if the portal is not in the part of the screen that the current view shows
     */
    public static @Nullable Crop computeCrop(
        Portal portal, Vec3 cameraPos, @Nullable Matrix4f screenClipMatrix, Mapping parent
    ) {
        int windowWidth = client.getWindow().getWidth();
        int windowHeight = client.getWindow().getHeight();

        // what the current view shows, in screen space
        float minX = parent.screenMinX();
        float maxX = parent.screenMaxX();
        float minY = parent.screenMinY();
        float maxY = parent.screenMaxY();

        Crop crop = new Crop();
        if (screenClipMatrix == null) {
            crop.fullSize = true;
            crop.width = windowWidth;
            crop.height = windowHeight;
            return crop;
        }

        float[] bounds = projectPortal(portal, cameraPos, screenClipMatrix);
        if (bounds != null) {
            minX = Math.max(minX, bounds[0]);
            maxX = Math.min(maxX, bounds[1]);
            minY = Math.max(minY, bounds[2]);
            maxY = Math.min(maxY, bounds[3]);
            if (minX >= maxX || minY >= maxY) {
                return null;
            }
        }
        // else a part of the portal is behind the camera: all that the current view shows

        int x0 = Math.max(0, (int) Math.floor((minX + 1) / 2 * windowWidth) - MARGIN);
        int x1 = Math.min(windowWidth, (int) Math.ceil((maxX + 1) / 2 * windowWidth) + MARGIN);
        int y0 = Math.max(0, (int) Math.floor((minY + 1) / 2 * windowHeight) - MARGIN);
        int y1 = Math.min(windowHeight, (int) Math.ceil((maxY + 1) / 2 * windowHeight) + MARGIN);
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }
        if ((float) (x1 - x0) * (y1 - y0) > FULL_SIZE_AREA_RATIO * windowWidth * windowHeight) {
            crop.fullSize = true;
            crop.width = windowWidth;
            crop.height = windowHeight;
            return crop;
        }
        crop.x0 = x0;
        crop.y0 = y0;
        crop.width = x1 - x0;
        crop.height = y1 - y0;
        return crop;
    }

    /**
     * The screen space bounds {minX, maxX, minY, maxY} of the portal's surface mesh,
     * or null if a part of it is behind the camera (or too close to its plane).
     */
    private static float @Nullable [] projectPortal(Portal portal, Vec3 cameraPos, Matrix4f screenClipMatrix) {
        float[] bounds = {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        boolean[] behindCamera = {false};
        Vector4f v = new Vector4f();
        portal.renderViewAreaMesh(
            portal.getOriginPos().subtract(cameraPos),
            (p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z) -> {
                addPoint(screenClipMatrix, v, p0x, p0y, p0z, bounds, behindCamera);
                addPoint(screenClipMatrix, v, p1x, p1y, p1z, bounds, behindCamera);
                addPoint(screenClipMatrix, v, p2x, p2y, p2z, bounds, behindCamera);
            }
        );
        if (behindCamera[0] || bounds[0] > bounds[1]) {
            return null;
        }
        return bounds;
    }

    private static void addPoint(
        Matrix4f matrix, Vector4f v, double x, double y, double z, float[] bounds, boolean[] behindCamera
    ) {
        matrix.transform(v.set((float) x, (float) y, (float) z, 1));
        if (v.w < 1.0E-3F) {
            behindCamera[0] = true;
            return;
        }
        float nx = v.x / v.w;
        float ny = v.y / v.w;
        bounds[0] = Math.min(bounds[0], nx);
        bounds[1] = Math.max(bounds[1], nx);
        bounds[2] = Math.min(bounds[2], ny);
        bounds[3] = Math.max(bounds[3], ny);
    }
}
