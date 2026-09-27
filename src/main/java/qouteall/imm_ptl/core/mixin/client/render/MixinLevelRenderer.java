package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
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
import qouteall.imm_ptl.core.render.PortalViewRenderer;
import qouteall.imm_ptl.core.render.VisibleSectionDiscovery;
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
 *     For views of other dimensions all of that runs normally (that dimension's renderer is only used by portal views).</li>
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
 * TODO(26.3): cross-portal entity rendering (CrossPortalEntityRenderer: an entity touching a portal is clipped by the portal
 *  plane and its projection is rendered on the other side). 1.21.1 hooked LevelRenderer.renderLevel around each renderEntity call
 *  and set up per-draw clip planes. In 26.3 entities are submitted from EntityRenderStates in LevelRenderer.submitEntities
 *  (no Entity reference, no per-draw clip planes); needs a new approach (e.g. per-render-state clip plane in extraction).
 * TODO(26.3): mirror face culling. With an odd number of mirrors the view transformation flips the winding order, so
 *  back-face culling culls the wrong faces (1.21.1 flipped the GL cull face around terrain layers and sky).
 *  Cull mode is fixed in 26.3 RenderPipelines; e.g. render the view with the un-mirrored camera and flip the view texture
 *  horizontally when sampling it on the portal surface.
 * TODO(26.3): depth clamp for portal views (IPGlobal.enableDepthClampForPortalRendering); renderpearl has no depth clamp.
 */
@Mixin(value = LevelRenderer.class)
public abstract class MixinLevelRenderer {

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
        if (PortalViewRenderer.isRenderingPortalView()) {
            if (ip_portalViewOcclusionGraph == null) {
                ip_portalViewOcclusionGraph = new VisibleSectionDiscovery.PortalViewOcclusionGraph(
                    (LevelRenderer) (Object) this, sectionOcclusionGraph
                );
            }
            cir.setReturnValue(ip_portalViewOcclusionGraph);
        }
    }

    /**
     * Don't re-center the main view's ViewArea to the portal view camera
     * (that resets all the sections that move in the grid, and invalidates the occlusion graph),
     * and don't change the camera position used for compile task priority.
     */
    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
    private void onRepositionCamera(CameraRenderState camera, CallbackInfo ci) {
        if (ip_isPortalViewWithMainLevelRenderer()) {
            ci.cancel();
        }
    }

    /**
     * Don't update the main view's occlusion graph from the portal view camera.
     * The loaded chunk and empty section changes are still applied,
     * because the view's extraction consumed them from the ClientChunkCache.
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
        if (ip_isPortalViewWithMainLevelRenderer()) {
            graph.updateLoadedChunks(
                chunkLoadingRenderState.addedLoadedChunks, chunkLoadingRenderState.removedLoadedChunks
            );
            graph.updateEmptySections(
                chunkLoadingRenderState.addedEmptySections, chunkLoadingRenderState.removedEmptySections
            );
            return;
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
