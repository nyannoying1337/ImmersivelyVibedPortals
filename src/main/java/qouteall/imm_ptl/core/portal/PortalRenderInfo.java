package qouteall.imm_ptl.core.portal;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;

/**
 * A portal's client-side rendering related data.
 * To access the package private field of Portal, this class is not in "render" package.
 * <p>
 * In 1.21.1 this held the GL occlusion queries used to decide (and predict, one frame late) whether a portal
 * is visible. 26.3 has no occlusion queries, so portal visibility is decided by CPU culling only
 * (see PortalViewRenderer).
 * TODO(26.3): occlusion-query based skipping of hidden portals (docs/rendering-26.3.md "Out for now").
 *  If a GPU visibility test is added later (e.g. reading back a few pixels of the portal surface
 *  a frame late), its per-portal state belongs here, keyed by {@code WorldRenderInfo.getRenderingDescription()}.
 */
@Environment(EnvType.CLIENT)
public class PortalRenderInfo {

    public static void init() {
        Portal.PORTAL_DISPOSE_SIGNAL.register(portal -> {
            if (portal.level().isClientSide()) {
                portal.portalRenderInfo = null;
            }
        });
    }

    @Nullable
    public static PortalRenderInfo getOptional(Portal portal) {
        Validate.isTrue(portal.level().isClientSide());

        return portal.portalRenderInfo;
    }

    public static PortalRenderInfo get(Portal portal) {
        Validate.isTrue(portal.level().isClientSide());

        if (portal.portalRenderInfo == null) {
            portal.portalRenderInfo = new PortalRenderInfo();
        }
        return portal.portalRenderInfo;
    }
}
