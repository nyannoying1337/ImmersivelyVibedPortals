package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ducks.IELevelRenderer_ViewGrid;
import qouteall.imm_ptl.core.render.EntityClipping;
import qouteall.imm_ptl.core.render.PortalViewRenderer;
import qouteall.imm_ptl.core.render.VisibleSectionDiscovery;
import qouteall.imm_ptl.core.mixin.client.accessor.IELevelRenderer_OcclusionGraph;
import qouteall.imm_ptl.core.render.context_management.DimensionRenderHelper;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * Portal views are rendered before the main view, each with its dimension's own {@link LevelRenderer}
 * (see docs/rendering-26.3.md). A portal view of the main view's dimension uses the main view's LevelRenderer,
 * so this mixin keeps such views from disturbing the main view's section state:
 * <ul>
 *     <li>During any portal view, the LevelRenderer's visible section lists are switched to separate lists,
 *     and {@link LevelRenderer#sectionOcclusionGraph()} returns a
 *     {@link VisibleSectionDiscovery.PortalViewOcclusionGraph}, so the vanilla {@code LevelExtractor.applyFrustum}
 *     computes the view's visible sections with {@link VisibleSectionDiscovery} every view,
 *     without touching the main view's lists or consuming the real graph's frustum update.</li>
 *     <li>During a portal view that uses the main view's LevelRenderer
 *     ({@link VisibleSectionDiscovery#isRenderingPortalViewWithMainLevelRenderer}):
 *     the ViewArea is not re-centered to the view camera and the section compile priority camera position
 *     is not changed (repositionCamera), the {@link SectionOcclusionGraph} is not updated
 *     (only its loaded chunk / empty section bookkeeping is), and translucent sections are not resorted.
 *     For views of other dimensions the graph is not updated either (views don't use it), translucent sections
 *     are resorted normally, and the ViewArea is not moved at render time: DimensionRenderHelper.selectForView
 *     moves it before the view is extracted ({@link #ip_moveGridForView}).</li>
 * </ul>
 * <p>
 * Hooks of 1.21.1 that were removed because the new design does not need them:
 * front clipping setup around layers/entities/weather (clipping is automatic in shaders, see PortalClipping),
 * the old renderer's before/after translucent hooks and framebuffer clearing (no recursive rendering anymore),
 * fog/lighting resets after portal rendering, fabulous translucent target workaround (no such target anymore),
 * ImmPtlViewArea construction, allChanged hooks (the extractor owns allChanged, see MixinLevelExtractor),
 * setupRender terrain override / spectator hack (replaced by the section list switching above; views disable smartCull),
 * pollLightUpdates world switching (not called by LevelRenderer anymore), isSectionCompiled (was for ImmPtlViewArea),
 * sky eye position (the sky is extracted from the view camera), glowing entity suppression
 * (the entity outline is only blitted for the main view: GameRenderer.render calls blitEntityOutline after the main view).
 * <p>
 * Cross-portal entity rendering: an entity touching a portal is clipped by the portal plane on the CPU
 *  (EntityClipping) and its projection is rendered on the other side (CrossPortalEntityRenderer).
 * Mirror face culling: views through an odd number of mirrors are rendered with an x-flipped projection (restores the
 *  triangle winding) and sampled with x flipped (PortalViewRenderer.renderCurrentView, PortalViewCrop).
 * Depth clamp: renderpearl has none; the portal surface shader emulates it (portal_view.vsh/fsh).
 */
@Mixin(value = LevelRenderer.class)
public abstract class MixinLevelRenderer implements IELevelRenderer_ViewGrid {

    @Shadow
    @Final
    @Mutable
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow
    @Final
    @Mutable
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> nearbyVisibleSections;

    @Shadow
    @Final
    private SectionOcclusionGraph sectionOcclusionGraph;

    @Shadow
    private @Nullable ViewArea viewArea;

    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

    @Shadow
    @Final
    private net.minecraft.client.renderer.WorldBorderRenderer worldBorderRenderer;

    @Unique
    private boolean ip_occlusionGraphStale = false;

    // the lists used by the main view (the vanilla instances)
    @Unique
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> ip_mainVisibleSections;
    @Unique
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> ip_mainNearbyVisibleSections;

    // the lists used by portal views
    @Unique
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> ip_viewVisibleSections =
        new ObjectArrayList<>();
    @Unique
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> ip_viewNearbyVisibleSections =
        new ObjectArrayList<>();

    @Unique
    private @Nullable VisibleSectionDiscovery.PortalViewOcclusionGraph ip_portalViewOcclusionGraph;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        ip_mainVisibleSections = visibleSections;
        ip_mainNearbyVisibleSections = nearbyVisibleSections;
    }

    /**
     * Switch the section list fields to the lists of the view being rendered.
     * Called at the head of every method that accesses the lists from outside {@link LevelRenderer#render}.
     */
    @Unique
    private void ip_selectSectionLists() {
        if (PortalViewRenderer.isRenderingPortalView()) {
            visibleSections = ip_viewVisibleSections;
            nearbyVisibleSections = ip_viewNearbyVisibleSections;
        }
        else {
            visibleSections = ip_mainVisibleSections;
            nearbyVisibleSections = ip_mainNearbyVisibleSections;
        }
    }

    @Unique
    private boolean ip_isPortalViewWithMainLevelRenderer() {
        return VisibleSectionDiscovery.isRenderingPortalViewWithMainLevelRenderer((LevelRenderer) (Object) this);
    }

    // the submits of the previous render (another view or the last frame) are built already
    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void onSubmitFeatures(CallbackInfo ci) {
        EntityClipping.onBeginSubmitFeatures();
    }

    @Inject(method = "visibleSections", at = @At("HEAD"))
    private void onVisibleSections(CallbackInfoReturnable<ObjectArrayList<SectionRenderDispatcher.RenderSection>> cir) {
        ip_selectSectionLists();
    }

    @Inject(method = "nearbyVisibleSections", at = @At("HEAD"))
    private void onNearbyVisibleSections(CallbackInfoReturnable<ObjectArrayList<SectionRenderDispatcher.RenderSection>> cir) {
        ip_selectSectionLists();
    }

    @Inject(method = "clearVisibleSections", at = @At("HEAD"))
    private void onClearVisibleSections(CallbackInfo ci) {
        ip_selectSectionLists();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(CallbackInfo ci) {
        ip_selectSectionLists();
    }

    // the sections are released, don't keep them in the view lists
    @Inject(method = "resetLevelRenderData", at = @At("HEAD"))
    private void onResetLevelRenderData(CallbackInfo ci) {
        ip_viewVisibleSections.clear();
        ip_viewNearbyVisibleSections.clear();
    }

    /**
     * During a portal view, LevelExtractor.extract/applyFrustum get the graph from here.
     * {@link LevelRenderer#render} uses the field directly, so it still updates the real graph.
     */
    @Inject(method = "sectionOcclusionGraph", at = @At("HEAD"), cancellable = true)
    private void onGetSectionOcclusionGraph(CallbackInfoReturnable<SectionOcclusionGraph> cir) {
        if (PortalViewRenderer.isRenderingPortalView()
            || VisibleSectionDiscovery.shouldUseSyncDiscoveryForMainView((LevelRenderer) (Object) this)
        ) {
            if (ip_portalViewOcclusionGraph == null) {
                ip_portalViewOcclusionGraph = new VisibleSectionDiscovery.PortalViewOcclusionGraph(
                    (LevelRenderer) (Object) this, sectionOcclusionGraph
                );
            }
            cir.setReturnValue(ip_portalViewOcclusionGraph);
        }
    }

    /**
     * In portal views the ViewArea isn't moved at render time: for a view of the main view's dimension that would
     * re-center the main view's grid to the view camera (resetting all the sections that move in the grid, and
     * invalidating the occlusion graph); for other renderers DimensionRenderHelper.selectForView moves it before the
     * view is extracted ({@link #ip_moveGridForView}), because the extraction already looks up the view's sections.
     */
    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
    private void onRepositionCamera(CameraRenderState camera, CallbackInfo ci) {
        if (PortalViewRenderer.isRenderingPortalView()) {
            ci.cancel();
        }
    }

    @Override
    public boolean ip_moveGridForView(SectionPos center, Vec3 cameraPos) {
        if (viewArea == null || sectionRenderDispatcher == null) {
            return false;
        }
        boolean moved = viewArea.repositionCamera(center);
        if (moved) {
            worldBorderRenderer.invalidate();
        }
        // compile task priority
        sectionRenderDispatcher.setCameraPosition(cameraPos);
        return moved;
    }

    /**
     * Don't update the occlusion graph from a portal view camera (views use VisibleSectionDiscovery).
     * The loaded chunk and empty section changes are still applied,
     * because the view's extraction consumed them from the ClientChunkCache.
     * The graph of another dimension's renderer is then not up to date when the player goes to that dimension
     * (it becomes the main view's renderer), so it's rebuilt at its next main view update.
     */
    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V"
        )
    )
    private void wrapSectionOcclusionGraphUpdate(
        SectionOcclusionGraph graph, CameraRenderState camera, int fov,
        ChunkLoadingRenderState chunkLoadingRenderState, Operation<Void> original
    ) {
        // (portal views of other dimensions don't use the graph either, see onGetSectionOcclusionGraph)
        if (PortalViewRenderer.isRenderingPortalView()) {
            if (!ip_isPortalViewWithMainLevelRenderer()) {
                ip_occlusionGraphStale = true;
            }
            graph.updateLoadedChunks(
                chunkLoadingRenderState.addedLoadedChunks, chunkLoadingRenderState.removedLoadedChunks
            );
            graph.updateEmptySections(
                chunkLoadingRenderState.addedEmptySections, chunkLoadingRenderState.removedEmptySections
            );
            // An extra helper's renderer: the dimension's own renderer needs these changes too,
            // otherwise sections that got blocks stay "empty" in its graph and are not rendered
            LevelRenderer owner = DimensionRenderHelper.getExtraHelperOwnerRenderer((LevelRenderer) (Object) this);
            if (owner != null) {
                SectionOcclusionGraph ownerGraph = ((IELevelRenderer_OcclusionGraph) owner).ip_getRealSectionOcclusionGraph();
                ownerGraph.updateLoadedChunks(
                    chunkLoadingRenderState.addedLoadedChunks, chunkLoadingRenderState.removedLoadedChunks
                );
                ownerGraph.updateEmptySections(
                    chunkLoadingRenderState.addedEmptySections, chunkLoadingRenderState.removedEmptySections
                );
            }
            return;
        }
        if (ip_occlusionGraphStale) {
            ip_occlusionGraphStale = false;
            graph.invalidate();
        }
        original.call(graph, camera, fov, chunkLoadingRenderState);
    }

    /**
     * Don't resort the main view's translucent sections for the portal view camera.
     * (The dirty sections found by the view are still compiled.)
     */
    @Inject(method = "scheduleTranslucentSectionResort", at = @At("HEAD"), cancellable = true)
    private void onScheduleTranslucentSectionResort(Vec3 cameraPos, CallbackInfo ci) {
        if (ip_isPortalViewWithMainLevelRenderer()) {
            ci.cancel();
        }
    }

    // don't render sky in fuse view portals (WorldRenderInfo.doRenderSky)
    @ModifyVariable(
        method = "render",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 1 // renderOutline, shouldRenderSky, consistentDepthRequired
    )
    private boolean modifyShouldRenderSky(boolean shouldRenderSky) {
        if (PortalViewRenderer.isRenderingPortalView() && WorldRenderInfo.isRendering()) {
            if (!WorldRenderInfo.getTopRenderInfo().doRenderSky) {
                return false;
            }
        }
        return shouldRenderSky;
    }
}
