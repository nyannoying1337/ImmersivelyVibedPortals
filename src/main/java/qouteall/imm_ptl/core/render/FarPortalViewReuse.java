package qouteall.imm_ptl.core.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Fewer updates for far portals: the view of a portal that is far away and small on the screen is rendered only every
 * 2nd frame (every 3rd when farther and smaller), and in between its last image is shown again (its target is kept,
 * {@link PortalViewTargetPool}). Only while nothing moved: the camera (position, rotation, projection with view
 * bobbing) and the portal must be exactly as when the image was rendered, otherwise it would be misaligned.
 * So it saves GPU time when standing still (building, in menus, AFK); the things seen through such a portal
 * (mobs, water) update at a lower rate then.
 * <p>
 * Only for portals seen from the main view (a reused image includes the portals seen in it), with cropped views
 * ({@link PortalViewCrop}, the rectangle must be the same too). Config: IPGlobal.reduceFarPortalUpdates
 * (on in the Performance and Balanced presets).
 */
public final class FarPortalViewReuse {
    private static final double MIN_DISTANCE = 32;
    private static final float MAX_AREA_RATIO = 0.05F;
    private static final double FARTHER_DISTANCE = 64;
    private static final float SMALLER_AREA_RATIO = 0.02F;

    private record Kept(
        PortalViewCrop.Mapping mapping, int x0, int y0, int width, int height,
        Matrix4f screenClipMatrix, Vec3 cameraPos,
        Vec3 portalOrigin, Vec3 portalAxisW, Vec3 portalAxisH, Vec3 portalDest,
        int renderedFrame, int interval
    ) {}

    // per portal (identity: global portals have no entity id)
    private static final Map<Portal, Kept> kept = new IdentityHashMap<>();

    /**
     * How often the portal's view must be rendered (in frames), 1 if every frame.
     */
    public static int getUpdateInterval(Portal portal, Vec3 cameraPos, PortalViewCrop.Crop crop) {
        if (!IPGlobal.reduceFarPortalUpdates || crop.fullSize) {
            return 1;
        }
        Minecraft client = Minecraft.getInstance();
        float areaRatio = (float) crop.width * crop.height
            / ((float) client.getWindow().getWidth() * client.getWindow().getHeight());
        double distance = portal.getDistanceToNearestPointInPortal(cameraPos);
        if (distance > FARTHER_DISTANCE && areaRatio < SMALLER_AREA_RATIO) {
            return 3;
        }
        if (distance > MIN_DISTANCE && areaRatio < MAX_AREA_RATIO) {
            return 2;
        }
        return 1;
    }

    /**
     * The mapping of the portal's last image if it can be shown again in this frame, else null.
     */
    public static PortalViewCrop.@Nullable Mapping getReusableMapping(
        Portal portal, Vec3 cameraPos, Matrix4f screenClipMatrix, PortalViewCrop.Crop crop, int interval
    ) {
        Kept k = kept.get(portal);
        if (k == null || interval <= 1) {
            return null;
        }
        if (RenderStates.frameIndex - k.renderedFrame >= Math.min(interval, k.interval)) {
            return null;
        }
        boolean same = k.x0 == crop.x0 && k.y0 == crop.y0 && k.width == crop.width && k.height == crop.height
            && k.screenClipMatrix.equals(screenClipMatrix)
            && k.cameraPos.equals(cameraPos)
            && k.portalOrigin.equals(portal.getOriginPos())
            && k.portalAxisW.equals(portal.getAxisW())
            && k.portalAxisH.equals(portal.getAxisH())
            && k.portalDest.equals(portal.getDestPos());
        return same ? k.mapping : null;
    }

    /**
     * The portal's view was rendered in this frame, into a target kept for it.
     */
    public static void onRendered(
        Portal portal, Vec3 cameraPos, Matrix4f screenClipMatrix, PortalViewCrop.Crop crop,
        PortalViewCrop.Mapping mapping, int interval
    ) {
        kept.put(portal, new Kept(
            mapping, crop.x0, crop.y0, crop.width, crop.height,
            new Matrix4f(screenClipMatrix), cameraPos,
            portal.getOriginPos(), portal.getAxisW(), portal.getAxisH(), portal.getDestPos(),
            RenderStates.frameIndex, interval
        ));
    }

    public static void forget(Portal portal) {
        kept.remove(portal);
    }

    /**
     * After the main view's portals were handled: forget the portals that were not seen in this frame
     * (their targets are freed by the pool).
     */
    public static void endFrame(java.util.Set<Portal> seenThisFrame) {
        kept.keySet().removeIf(portal -> !seenThisFrame.contains(portal));
    }

    public static void cleanUp() {
        kept.clear();
    }
}
