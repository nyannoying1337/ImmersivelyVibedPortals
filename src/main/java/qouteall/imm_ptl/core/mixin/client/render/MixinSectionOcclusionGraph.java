package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SectionOcclusionGraph.class)
public class MixinSectionOcclusionGraph {
    /**
     * The full graph update runs on a background thread and reads the camera state when it runs.
     * Vanilla passes the shared, mutable CameraRenderState (and its blockPos is the camera's mutable position).
     * With portal views, the render thread overwrites that state with portal view cameras
     * (in other dimensions/positions) in the meantime, so the graph could be built around the wrong position,
     * leaving the main view without terrain until the next full update.
     * Give the background task a snapshot of what it reads (blockPos, pos, smartCull).
     */
    @ModifyVariable(method = "scheduleFullUpdate", at = @At("HEAD"), argsOnly = true)
    private CameraRenderState snapshotCamera(CameraRenderState camera) {
        CameraRenderState copy = new CameraRenderState();
        copy.blockPos = camera.blockPos.immutable();
        copy.pos = camera.pos;
        copy.smartCull = camera.smartCull;
        copy.initialized = camera.initialized;
        copy.isFrustumCaptured = camera.isFrustumCaptured;
        return copy;
    }
}
