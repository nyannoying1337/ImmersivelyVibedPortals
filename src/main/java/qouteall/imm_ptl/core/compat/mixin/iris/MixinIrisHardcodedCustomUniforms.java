package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.uniforms.HardcodedCustomUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.iris_compatibility.PortalViewEyeLight;

// the sky light at the eyes (eyeBrightnessM, eyeInCave) at the portal view camera, see PortalViewEyeLight
@Mixin(value = HardcodedCustomUniforms.class, remap = false)
public class MixinIrisHardcodedCustomUniforms {
    @Inject(method = "getEyeSkyBrightness", at = @At("HEAD"), cancellable = true)
    private static void onGetEyeSkyBrightness(CallbackInfoReturnable<Float> cir) {
        int[] light = PortalViewEyeLight.get();
        if (light != null) {
            cir.setReturnValue(light[1] * 16f);
        }
    }
}
