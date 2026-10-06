package qouteall.imm_ptl.core.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.mixin.client.accessor.IELevelRenderer_OcclusionGraph;
import qouteall.imm_ptl.core.mixin.client.accessor.IESectionOcclusionGraph;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Future;

/**
 * Skips the views of portals that are hidden behind blocks from the main camera
 * (1.21.1 used GL occlusion queries; 26.3 has none). See docs/rendering-26.3.md "Hidden portals".
 * <p>
 * Vanilla's {@link SectionOcclusionGraph} (cave culling) is a breadth-first search from the camera section through
 * the section faces that can see each other, independent of the camera rotation. A section without a node in it
 * can't be seen from the camera, so a portal is hidden when none of the sections its box touches has a node.
 * Whenever that can't be decided reliably, the portal is rendered.
 * <p>
 * Only for portals seen from the main view: portal views don't update the graph (see MixinLevelRenderer).
 * <p>
 * With Sodium, which replaces the vanilla graph: Sodium's culling result of the main view (frustum and occlusion,
 * {@code SodiumWorldRenderer.isBoxVisible}). Portal views don't change it (MixinSodiumRenderSectionManager), but it is
 * from the last frame or older (Sodium culls asynchronously), with the camera of then: a portal that just turned into
 * view would count as hidden. So a portal is only skipped after it has been in the frustum for
 * {@link #SODIUM_IN_VIEW_FRAMES} frames in a row.
 */
@Environment(EnvType.CLIENT)
public class PortalOcclusionCulling {
    // portals nearer than this are always rendered
    private static final double MIN_CULLING_DISTANCE = 8;
    // portals touching more sections than this are always rendered
    private static final int MAX_SECTIONS = 64;
    // a portal found visible keeps being rendered for this many frames (partial graph updates can't make it flicker)
    private static final int KEEP_VISIBLE_FRAMES = 10;
    // with Sodium, a portal must have been in the frustum for this many frames before it can be skipped
    private static final int SODIUM_IN_VIEW_FRAMES = 10;

    private static final BlockPos.MutableBlockPos tempPos = new BlockPos.MutableBlockPos();

    /**
     * Must be called with the main view's state (before any portal view is set up).
     */
    public static boolean isHidden(Portal portal, Vec3 cameraPos) {
        if (!IPCGlobal.cullHiddenPortals) {
            return false;
        }
        if (portal.getIsGlobal()) {
            return false;
        }

        // called each frame for the portals in the main view's frustum
        PortalRenderInfo renderInfo = PortalRenderInfo.get(portal);
        int frame = RenderStates.frameIndex;
        if (renderInfo.lastInFrustumFrame < frame - 1) {
            renderInfo.inFrustumSinceFrame = frame;
        }
        renderInfo.lastInFrustumFrame = frame;

        if (whyVisible(portal, cameraPos) != null) {
            renderInfo.lastVisibleFrame = RenderStates.frameIndex;
            return false;
        }
        return RenderStates.frameIndex - renderInfo.lastVisibleFrame > KEEP_VISIBLE_FRAMES;
    }

    /**
     * Why the portal counts as visible from the main camera now, or null if it's hidden (for tests and debugging).
     */
    public static @Nullable String whyVisible(Portal portal, Vec3 cameraPos) {
        if (portal.getDistanceToNearestPointInPortal(cameraPos) < MIN_CULLING_DISTANCE) {
            return "near";
        }

        if (SodiumInterface.invoker.isSodiumPresent()) {
            return whyVisibleWithSodium(portal);
        }

        LevelRenderer levelRenderer = Minecraft.getInstance().levelRenderer;
        // right after a teleport, the main view's sections are found without the graph (it's not ready)
        if (VisibleSectionDiscovery.shouldUseSyncDiscoveryForMainView(levelRenderer)) {
            return "just teleported";
        }
        ViewArea viewArea = levelRenderer.viewArea();
        if (viewArea == null) {
            return "no view area";
        }

        SectionOcclusionGraph graph = ((IELevelRenderer_OcclusionGraph) levelRenderer).ip_getRealSectionOcclusionGraph();
        // being rebuilt (the camera moved): it may not match the camera yet
        IESectionOcclusionGraph ieGraph = (IESectionOcclusionGraph) graph;
        Future<?> fullUpdateTask = ieGraph.ip_getFullUpdateTask();
        if (ieGraph.ip_needsFullUpdate() || (fullUpdateTask != null && !fullUpdateTask.isDone())) {
            return "graph being rebuilt";
        }

        AABB box = portal.getThinBoundingBox().inflate(0.5);
        int minX = SectionPos.posToSectionCoord(box.minX);
        int minY = SectionPos.posToSectionCoord(box.minY);
        int minZ = SectionPos.posToSectionCoord(box.minZ);
        int maxX = SectionPos.posToSectionCoord(box.maxX);
        int maxY = SectionPos.posToSectionCoord(box.maxY);
        int maxZ = SectionPos.posToSectionCoord(box.maxZ);
        long sectionCount = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (sectionCount > MAX_SECTIONS) {
            return "too many sections";
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(tempPos.set(
                        SectionPos.sectionToBlockCoord(x), SectionPos.sectionToBlockCoord(y), SectionPos.sectionToBlockCoord(z)
                    ));
                    // outside the grid (render distance or world height): can't tell
                    if (section == null) {
                        return "section outside the grid " + x + " " + y + " " + z;
                    }
                    if (graph.getNode(section) != null) {
                        return "section reachable " + x + " " + y + " " + z;
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable String whyVisibleWithSodium(Portal portal) {
        PortalRenderInfo renderInfo = PortalRenderInfo.get(portal);
        if (RenderStates.frameIndex - renderInfo.inFrustumSinceFrame < SODIUM_IN_VIEW_FRAMES) {
            return "just came into view";
        }
        AABB box = portal.getThinBoundingBox().inflate(0.5);
        if ((box.getXsize() / 16 + 1) * (box.getYsize() / 16 + 1) * (box.getZsize() / 16 + 1) > MAX_SECTIONS) {
            return "too many sections";
        }
        if (SodiumInterface.invoker.isBoxVisible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
            return "visible in Sodium's culling";
        }
        return null;
    }
}
