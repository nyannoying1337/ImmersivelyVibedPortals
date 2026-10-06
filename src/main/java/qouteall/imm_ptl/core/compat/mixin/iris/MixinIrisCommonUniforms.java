package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.uniforms.CommonUniforms;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.iris_compatibility.PortalViewEyeLight;

// eyeBrightness at the portal view camera, see PortalViewEyeLight
@Mixin(value = CommonUniforms.class, remap = false)
public class MixinIrisCommonUniforms {
    @Inject(method = "getEyeBrightness", at = @At("HEAD"), cancellable = true)
    private static void onGetEyeBrightness(CallbackInfoReturnable<Vector2i> cir) {
        int[] light = PortalViewEyeLight.get();
        if (light != null) {
            cir.setReturnValue(new Vector2i(light[0] * 16, light[1] * 16));
        }
    }
}
