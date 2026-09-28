package qouteall.imm_ptl.core.compat.mixin.iris;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.PortalClipping;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Front clipping for shaderpack terrain programs, see {@link PortalClipping#transformIrisSodiumProgram}.
 * Shadow programs are left alone (the clip plane is for the player's view).
 */
@Mixin(value = TransformPatcher.class, remap = false)
public class MixinIrisTransformPatcher {
    @Inject(method = "patchSodium", at = @At("RETURN"), cancellable = true)
    private static void onPatchSodium(
        String name, String vertex, String geometry, String tessControl, String tessEval, String fragment,
        AlphaTest alpha, Object2ObjectMap<?, ?> textureMap, Set<String> textureOverrides, boolean shadow,
        CallbackInfoReturnable<Map<PatchShaderType, String>> cir
    ) {
        Map<PatchShaderType, String> result = cir.getReturnValue();
        if (result == null || shadow) {
            return;
        }

        Map<String, String> sources = new HashMap<>();
        result.forEach((type, source) -> sources.put(type.name(), source));
        Map<String, String> transformed = PortalClipping.transformIrisSodiumProgram(sources);
        if (transformed == null) {
            return;
        }

        // (a new map: the returned one is cached by Iris)
        EnumMap<PatchShaderType, String> newResult = new EnumMap<>(PatchShaderType.class);
        result.forEach((type, source) -> newResult.put(type, transformed.get(type.name())));
        cir.setReturnValue(newResult);
    }
}
