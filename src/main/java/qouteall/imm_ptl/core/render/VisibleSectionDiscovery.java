package qouteall.imm_ptl.core.render;

import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Octree;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.chunk_loading.PerformanceLevel;
import qouteall.imm_ptl.core.ducks.IERenderSection;
import qouteall.imm_ptl.core.miscellaneous.ClientPerformanceMonitor;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.nether_portal.BlockTraverse;
import qouteall.imm_ptl.core.render.context_management.DimensionRenderHelper;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Discover visible sections by breadth-first traverse, for portal views.
 * See docs/rendering-26.3.md "Culling and section visibility for views".
 * <p>
 * Vanilla's {@link SectionOcclusionGraph} is rooted at one camera per {@link LevelRenderer} and is updated asynchronously.
 * Portal view cameras are very dynamic, and a portal view of the main view's dimension shares the main view's
 * {@link LevelRenderer}, so its graph must not be re-rooted. So in portal views the visible sections are found
 * synchronously here, from the {@link ViewArea} of the view's LevelRenderer.
 * No cave culling (the view camera is usually right behind the destination portal, inside blocks).
 * <p>
 * The hook: during a portal view, {@link LevelRenderer#sectionOcclusionGraph()} returns a {@link PortalViewOcclusionGraph}
 * (see MixinLevelRenderer), so the vanilla {@code LevelExtractor.applyFrustum} fills the view's section lists using this.
 * <p>
 * Limitation: the vanilla {@link ViewArea} is a fixed grid (render distance radius) around its LevelRenderer's camera.
 * For a portal view of the main view's dimension that grid stays centered on the main camera,
 * so only the sections within the main view's render distance can be rendered in that view.
 * (1.21.1 used ImmPtlViewArea, a hash-map based ViewArea, to avoid that. 26.3 has no ViewArea replacement point:
 * the ViewArea's RotatingSectionStorage indexes are used by SectionOcclusionGraph's arrays.)
 * TODO(26.3): render far-away sections of the same dimension in portal views (e.g. a second ViewArea +
 * SectionRenderDispatcher for such views, or a dedicated LevelRenderer per far-away portal view).
 */
@Environment(EnvType.CLIENT)
public class VisibleSectionDiscovery {
    // sections nearer than this are "nearby" (vanilla uses 32 in SectionOcclusionGraph.addSectionsInFrustum)
    private static final int NEARBY_DISTANCE = 32;

    private static @Nullable ViewArea viewArea;
    private static @Nullable Frustum frustum;
    private static @Nullable List<RenderSection> visibleResult;
    private static @Nullable List<RenderSection> nearbyResult;
    private static final ArrayDeque<RenderSection> tempQueue = new ArrayDeque<>();
    private static final BlockPos.MutableBlockPos tempPos = new BlockPos.MutableBlockPos();
    private static Vec3 cameraPos = Vec3.ZERO;
    private static SectionPos cameraSectionPos = SectionPos.of(0, 0, 0);
    private static long timeMark;
    private static int viewDistance;

    /**
     * Whether a portal view is being rendered with the {@link LevelRenderer} that the main view uses
     * (the view's dimension is the main view's dimension).
     * Note that during a portal view {@code Minecraft.levelRenderer} is switched to the view's dimension's renderer,
     * so it cannot be used to know the main view's renderer.
     */
    /**
     * Vanilla computes the main view's visible sections on a background thread, so right after
     * the player teleports the new dimension's main view has no (or stale) visible sections for a few frames,
     * which shows as a flash. For those frames the main view uses this class's synchronous discovery too.
     */
    private static int syncDiscoveryUntilFrame = -1;
    
    public static void onPlayerTeleported() {
        syncDiscoveryUntilFrame = RenderStates.frameIndex + 3;
    }
    
    public static boolean shouldUseSyncDiscoveryForMainView(LevelRenderer levelRenderer) {
        return RenderStates.frameIndex <= syncDiscoveryUntilFrame
            && levelRenderer == Minecraft.getInstance().levelRenderer
            && !PortalViewRenderer.isRenderingPortalView();
    }
    
    public static boolean isRenderingPortalViewWithMainLevelRenderer(LevelRenderer levelRenderer) {
        if (!PortalViewRenderer.isRenderingPortalView()) {
            return false;
        }
        if (RenderStates.originalPlayerDimension == null) {
            return false;
        }
        DimensionRenderHelper mainHelper =
            ClientWorldLoader.RENDER_HELPER_MAP.get(RenderStates.originalPlayerDimension);
        return mainHelper != null && mainHelper.levelRenderer == levelRenderer;
    }

    /**
     * @param frustum the culling frustum of the view camera (it is copied, not modified)
     */
    public static void discoverVisibleSections(
        ClientLevel world,
        ViewArea viewArea_,
        Frustum frustum_,
        List<RenderSection> visibleResult_,
        List<RenderSection> nearbyResult_
    ) {
        cameraPos = new Vec3(frustum_.getCamX(), frustum_.getCamY(), frustum_.getCamZ());
        // same as vanilla SectionOcclusionGraph.offsetFrustum
        frustum = new Frustum(frustum_).offsetToFullyIncludeCameraCube(8);
        viewArea = viewArea_;
        visibleResult = visibleResult_;
        nearbyResult = nearbyResult_;

        tempQueue.clear();

        viewDistance = Math.min(
            PerformanceLevel.getPortalRenderingDistance(
                ClientPerformanceMonitor.level, WorldRenderInfo.getRenderDistance()
            ),
            viewArea.getViewDistance()
        );

        timeMark = System.nanoTime();

        cameraSectionPos = SectionPos.of(BlockPos.containing(cameraPos));

        try {
            SectionPos modifiedVisibleSectionIterationOrigin = null;
            if (PortalRendering.isRendering()) {
                Portal renderingPortal = PortalRendering.getRenderingPortal();
                modifiedVisibleSectionIterationOrigin = renderingPortal.getPortalShape()
                    .getModifiedVisibleSectionIterationOrigin(renderingPortal, cameraPos);
            }

            if (modifiedVisibleSectionIterationOrigin != null) {
                checkSection(
                    modifiedVisibleSectionIterationOrigin.getX(),
                    modifiedVisibleSectionIterationOrigin.getY(),
                    modifiedVisibleSectionIterationOrigin.getZ(),
                    true
                );
            }
            else if (cameraPos.y < world.getMinY()) {
                discoverBottomOrTopLayerVisibleChunks(viewArea.minSectionY());
            }
            else if (cameraPos.y > (world.getMaxY() + 1)) {
                discoverBottomOrTopLayerVisibleChunks(viewArea.maxSectionY());
            }
            else {
                checkSection(
                    cameraSectionPos.x(),
                    cameraSectionPos.y(),
                    cameraSectionPos.z(),
                    true
                );
            }

            // breadth-first searching
            while (!tempQueue.isEmpty()) {
                RenderSection curr = tempQueue.poll();
                long sectionNode = curr.getSectionNode();
                int cx = SectionPos.x(sectionNode);
                int cy = SectionPos.y(sectionNode);
                int cz = SectionPos.z(sectionNode);

                checkSection(cx + 1, cy, cz, false);
                checkSection(cx - 1, cy, cz, false);
                checkSection(cx, cy + 1, cz, false);
                checkSection(cx, cy - 1, cz, false);
                checkSection(cx, cy, cz + 1, false);
                checkSection(cx, cy, cz - 1, false);
            }
        }
        finally {
            // avoid memory leak
            tempQueue.clear();
            visibleResult = null;
            nearbyResult = null;
            viewArea = null;
            frustum = null;
        }
    }

    // NOTE the vanilla frustum culling code may wrongly cull the first section
    private static boolean isVisible(RenderSection section) {
        AABB box = section.getBoundingBox();
        return frustum.isVisible(box);
    }

    private static boolean isNearby(RenderSection section) {
        AABB box = section.getBoundingBox();
        return cameraPos.x > box.minX - NEARBY_DISTANCE && cameraPos.x < box.maxX + NEARBY_DISTANCE
            && cameraPos.y > box.minY - NEARBY_DISTANCE && cameraPos.y < box.maxY + NEARBY_DISTANCE
            && cameraPos.z > box.minZ - NEARBY_DISTANCE && cameraPos.z < box.maxZ + NEARBY_DISTANCE;
    }

    private static void discoverBottomOrTopLayerVisibleChunks(int cy) {
        BlockTraverse.<Object>searchOnPlane(
            cameraSectionPos.x(),
            cameraSectionPos.z(),
            viewDistance - 1,
            (cx, cz) -> {
                checkSection(cx, cy, cz, false);
                return null;
            }
        );
    }

    private static void checkSection(int cx, int cy, int cz, boolean skipFrustumTest) {
        if (Math.abs(cx - cameraSectionPos.x()) > viewDistance) {
            return;
        }
        if (Math.abs(cy - cameraSectionPos.y()) > viewDistance) {
            return;
        }
        if (Math.abs(cz - cameraSectionPos.z()) > viewDistance) {
            return;
        }

        // returns null if the section is outside the ViewArea grid
        RenderSection section = viewArea.getRenderSectionAt(
            tempPos.set(
                SectionPos.sectionToBlockCoord(cx),
                SectionPos.sectionToBlockCoord(cy),
                SectionPos.sectionToBlockCoord(cz)
            )
        );
        if (section != null) {
            IERenderSection ieRenderSection = (IERenderSection) section;
            if (ieRenderSection.portal_getMark() != timeMark) {
                ieRenderSection.portal_setMark(timeMark);// mark it checked
                if (skipFrustumTest || isVisible(section)) {
                    tempQueue.add(section);
                    visibleResult.add(section);
                    if (isNearby(section)) {
                        nearbyResult.add(section);
                    }
                }
            }
        }
    }

    /**
     * Used as {@link LevelRenderer#sectionOcclusionGraph()} while rendering a portal view (see MixinLevelRenderer).
     * It makes {@code LevelExtractor.extract} always re-apply the frustum for the view
     * (without consuming the real graph's frustum update flag, which the main view needs),
     * and computes the view's visible sections with {@link #discoverVisibleSections}.
     * Everything else is delegated to the LevelRenderer's real graph.
     */
    public static final class PortalViewOcclusionGraph extends SectionOcclusionGraph {
        private final LevelRenderer levelRenderer;
        private final SectionOcclusionGraph delegate;

        public PortalViewOcclusionGraph(LevelRenderer levelRenderer, SectionOcclusionGraph delegate) {
            this.levelRenderer = levelRenderer;
            this.delegate = delegate;
        }

        @Override
        public boolean consumeFrustumUpdate() {
            return true;
        }

        @Override
        public void addSectionsInFrustum(
            Frustum frustum, List<RenderSection> visibleSections, List<RenderSection> nearbyVisibleSection
        ) {
            ViewArea viewArea = levelRenderer.viewArea();
            // during a portal view, Minecraft.level is the view's level
            ClientLevel world = Minecraft.getInstance().level;
            if (viewArea == null || world == null) {
                return;
            }
            discoverVisibleSections(world, viewArea, frustum, visibleSections, nearbyVisibleSection);
        }

        // delegation

        @Override
        public void waitAndReset(@Nullable ViewArea viewArea) {
            delegate.waitAndReset(viewArea);
        }

        @Override
        public LongCollection expectedChunks() {
            return delegate.expectedChunks();
        }

        @Override
        public void invalidate() {
            delegate.invalidate();
        }

        @Override
        public void invalidateIfNeeded(CameraRenderState camera, int fov) {
            delegate.invalidateIfNeeded(camera, fov);
        }

        @Override
        public void schedulePropagationFrom(RenderSection section) {
            delegate.schedulePropagationFrom(section);
        }

        @Override
        public void update(CameraRenderState camera, int fov, ChunkLoadingRenderState chunkLoadingRenderState) {
            delegate.update(camera, fov, chunkLoadingRenderState);
        }

        @Override
        public @Nullable Node getNode(RenderSection section) {
            return delegate.getNode(section);
        }

        @Override
        public void updateEmptySections(LongOpenHashSet added, LongOpenHashSet removed) {
            delegate.updateEmptySections(added, removed);
        }

        @Override
        public void updateLoadedChunks(LongOpenHashSet added, LongOpenHashSet removed) {
            delegate.updateLoadedChunks(added, removed);
        }

        @Override
        public Octree getOctree() {
            return delegate.getOctree();
        }
    }
}
