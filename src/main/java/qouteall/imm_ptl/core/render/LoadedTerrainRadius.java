package qouteall.imm_ptl.core.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * How far around a portal's destination the client has the terrain.
 * <p>
 * The server loads the other side of a portal less far than the render distance (ChunkVisibility: less the further
 * the player is from the portal, capped by indirectLoadingRadiusCap and the performance level). A view rendered as
 * far as the render distance then showed the sky past the loaded chunks, with a hard edge (or chunks loaded for other
 * reasons floating in it). So a portal view is rendered (and fogged) only as far as this radius
 * (PortalRenderer.getPortalRenderDistance), like the normal view at the render distance.
 * <p>
 * The radius is the largest Chebyshev ring of chunks around the destination chunk of which at least 90% is loaded,
 * measured again every {@link #UPDATE_INTERVAL_FRAMES} frames (it grows while the chunks arrive).
 */
@Environment(EnvType.CLIENT)
public class LoadedTerrainRadius {
    private static final int UPDATE_INTERVAL_FRAMES = 10;
    private static final int MIN_RADIUS = 2;

    /**
     * @return the radius in chunks, at most {@code maxRadius}
     */
    public static int get(Portal portal, int maxRadius) {
        PortalRenderInfo renderInfo = PortalRenderInfo.get(portal);
        if (RenderStates.frameIndex - renderInfo.loadedRadiusFrame >= UPDATE_INTERVAL_FRAMES
            || renderInfo.loadedRadiusMax != maxRadius
        ) {
            renderInfo.loadedRadius = measure(portal, maxRadius);
            renderInfo.loadedRadiusFrame = RenderStates.frameIndex;
            renderInfo.loadedRadiusMax = maxRadius;
        }
        return renderInfo.loadedRadius;
    }

    private static int measure(Portal portal, int maxRadius) {
        ClientLevel destLevel = ClientWorldLoader.getOptionalWorld(portal.getDestDim());
        if (destLevel == null) {
            return maxRadius;
        }
        Vec3 dest = portal.getDestPos();
        int centerX = SectionPos.blockToSectionCoord(dest.x);
        int centerZ = SectionPos.blockToSectionCoord(dest.z);
        for (int r = 1; r <= maxRadius; r++) {
            int loaded = 0;
            for (int i = -r; i <= r; i++) {
                loaded += loadedCount(destLevel, centerX + i, centerZ - r);
                loaded += loadedCount(destLevel, centerX + i, centerZ + r);
            }
            for (int i = -r + 1; i <= r - 1; i++) {
                loaded += loadedCount(destLevel, centerX - r, centerZ + i);
                loaded += loadedCount(destLevel, centerX + r, centerZ + i);
            }
            if (loaded < 0.9 * 8 * r) {
                return Math.max(r - 1, MIN_RADIUS);
            }
        }
        return maxRadius;
    }

    private static int loadedCount(ClientLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().hasChunk(chunkX, chunkZ) ? 1 : 0;
    }
}
