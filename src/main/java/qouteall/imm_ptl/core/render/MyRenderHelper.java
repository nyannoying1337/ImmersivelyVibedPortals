package qouteall.imm_ptl.core.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.DimensionRenderHelper;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * Misc rendering helpers.
 * The 1.21.1 GL/stencil/immediate-mode helpers (framebuffer blitting, portal area drawing,
 * face culling switch, GL debug readback, the .json core shaders) are gone:
 * in 26.3 portal views are rendered into offscreen targets by {@link PortalViewRenderer}
 * and composited by {@link PortalSurfaceRendering}. See docs/rendering-26.3.md.
 */
public class MyRenderHelper {

    public static final Minecraft client = Minecraft.getInstance();

    /**
     * In 26.3 only the client's current level runs its light engine
     * ({@code Minecraft.renderFrame} calls {@link ClientLevel#update()} for it; rendering doesn't).
     * The light update queues of the other levels are polled in {@link ClientWorldLoader#tick()},
     * and their light engines run here. Otherwise the light sections that are marked to be removed are never removed
     * (minor memory leak, and wrongly removed light data when the chunks get reloaded to client).
     * This should not run before world rendering, or the smooth lighting may become abnormal in section edge.
     */
    public static void lateUpdateLight() {
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }

        for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            if (world != client.level) {
                world.getChunkSource().getLightEngine().runLightUpdates();
            }
        }
    }

    /**
     * Upload the compiled terrain buffers of the dimensions other than the current one.
     * Vanilla only uploads them at the end of {@code LevelRenderer.render},
     * so without this the compiled sections of a dimension that is not being rendered pile up in the staging buffer.
     */
    public static void earlyRemoteUpload() {
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }

        for (DimensionRenderHelper helper : ClientWorldLoader.RENDER_HELPER_MAP.values()) {
            if (helper.levelRenderer == client.levelRenderer) {
                continue;
            }
            SectionRenderDispatcher dispatcher = helper.levelRenderer.sectionRenderDispatcher();
            if (dispatcher == null) {
                continue;
            }
            dispatcher.lock();
            try {
                dispatcher.uploadTerrainBuffersToGpu();
            }
            finally {
                dispatcher.unlock();
            }
        }
    }

    public static float transformFogDistance(float value) {
        if (!WorldRenderInfo.isFogEnabled()) {
            return value * 23333;
        }

        // just disable fog for fuse-view portals for now
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();

            if (renderingPortal.isFuseView()) {
                return value * 23333;
            }
        }

        // as non-fuse-view portals does not apply scale transformation to modelview,
        // there is no need to transform fog distance (both with and without sodium)

        return value;
    }
}
