package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.async.CullTask;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumRenderSectionManager;
import qouteall.imm_ptl.core.render.PortalViewRenderer;

/**
 * Sodium culls sections asynchronously and keeps the resulting trees per renderer, i.e. per dimension.
 * A portal view of the same dimension as the main view (or as another view) would get trees made for
 * another camera, and would feed trees made for its own camera to the main view.
 * So portal views don't touch the async culling at all and collect their visible sections synchronously
 * with Sodium's fallback traversal, which only depends on the current viewport.
 * <p>
 * But a cull task that is still running must be finished: while it runs, section adds and removes are queued
 * (QueuedSectionStorage's safe read phase), and only consuming its result ends that. A task of the main view can still
 * be running when its renderer is used only by portal views from then on (the player changed dimension), and then the
 * renderer never got the chunks loaded afterwards: no terrain in views of the old dimension.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class MixinSodiumRenderSectionManager implements IESodiumRenderSectionManager {
    @Shadow
    private boolean needsRenderListUpdate;

    @Shadow
    private DeferredTaskList taskLists;

    @Shadow
    private CullTask pendingTask;

    @Shadow
    @Final
    private SectionStorage renderSections;

    @Shadow
    protected abstract void consumeCullTaskResults(boolean waitForCompletion);

    @Shadow
    public abstract int getVisibleChunkCount();

    @Override
    public int ip_getSectionsWithGeometry() {
        return getVisibleChunkCount();
    }

    @Override
    public int ip_getPendingBuilds() {
        return taskLists == null ? 0 : taskLists.size();
    }

    @Shadow
    protected abstract void renderOutOfGraph(Viewport viewport, FogParameters fogParameters);

    @Inject(method = "prepareRenderTrees", at = @At("HEAD"), cancellable = true)
    private void onPrepareRenderTrees(
        Viewport viewport, FogParameters fogParameters, boolean useOcclusionCulling, CallbackInfo ci
    ) {
        if (PortalViewRenderer.isRenderingPortalView()) {
            // like prepareRenderTrees, without scheduling a new task
            if (pendingTask != null && pendingTask.cancelIfNotStarted()) {
                pendingTask = null;
                renderSections.endSafeReadPhase();
            }
            consumeCullTaskResults(false);
            ci.cancel();
        }
    }

    @Inject(method = "finalizeRenderLists", at = @At("HEAD"), cancellable = true)
    private void onFinalizeRenderLists(
        Camera camera, Viewport viewport, FogParameters fogParameters, boolean updateChunksImmediately,
        CallbackInfo ci
    ) {
        if (PortalViewRenderer.isRenderingPortalView()) {
            this.renderOutOfGraph(viewport, fogParameters);
            // the next view (usually the main view) must build its own render lists again
            this.needsRenderListUpdate = true;
            ci.cancel();
        }
    }
}
