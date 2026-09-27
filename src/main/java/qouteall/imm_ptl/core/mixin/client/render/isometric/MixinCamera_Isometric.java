package qouteall.imm_ptl.core.mixin.client.render.isometric;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.TransformationManager;

/**
 * In 26.3 the world projection matrix comes from the camera's render state
 * (GameRenderer.getProjectionMatrix no longer exists).
 */
@Mixin(Camera.class)
public class MixinCamera_Isometric {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void onExtractRenderState(
        CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci
    ) {
        if (TransformationManager.isIsometricView) {
            cameraState.projectionMatrix.set(TransformationManager.getIsometricProjection());
        }
    }
}
