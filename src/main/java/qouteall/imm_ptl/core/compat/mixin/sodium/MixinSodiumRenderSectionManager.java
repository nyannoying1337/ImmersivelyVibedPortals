package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.PortalViewRenderer;

/**
 * Sodium culls sections asynchronously and keeps the resulting trees per renderer, i.e. per dimension.
 * A portal view of the same dimension as the main view (or as another view) would get trees made for
 * another camera, and would feed trees made for its own camera to the main view.
 * So portal views don't touch the async culling at all and collect their visible sections synchronously
 * with Sodium's fallback traversal, which only depends on the current viewport.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class MixinSodiumRenderSectionManager {
    @Shadow
    private boolean needsRenderListUpdate;

    @Shadow
    protected abstract void renderOutOfGraph(Viewport viewport, FogParameters fogParameters);

    @Inject(method = "prepareRenderTrees", at = @At("HEAD"), cancellable = true)
    private void onPrepareRenderTrees(
        Viewport viewport, FogParameters fogParameters, boolean useOcclusionCulling, CallbackInfo ci
    ) {
        if (PortalViewRenderer.isRenderingPortalView()) {
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
