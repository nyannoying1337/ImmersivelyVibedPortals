package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.PortalClipping;

import java.util.HashMap;
import java.util.Map;

/**
 * Front clipping for Iris' fallback programs (used for the render types a shaderpack has no program for),
 * see {@link PortalClipping#transformIrisFallbackProgram}. The shadow fallback (createFallbackShadow) is left alone.
 */
@Mixin(value = ShaderCreator.class, remap = false)
public class MixinIrisShaderCreator {
    @WrapOperation(
        method = "createFallback",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/programs/ShaderCreator;link(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/mojang/renderpearl/api/vertex/VertexFormat;Z)Lnet/irisshaders/iris/pipeline/programs/PartialShader;"
        )
    )
    private static PartialShader wrapLinkFallback(
        String name, String vertex, String geometry, String tessControl, String tessEval, String fragment,
        VertexFormat vertexFormat, boolean isFallback, Operation<PartialShader> original
    ) {
        if (geometry == null && tessControl == null && tessEval == null) {
            Map<String, String> sources = new HashMap<>();
            sources.put("VERTEX", vertex);
            sources.put("FRAGMENT", fragment);
            Map<String, String> transformed = PortalClipping.transformIrisFallbackProgram(sources);
            if (transformed != null) {
                vertex = transformed.get("VERTEX");
                fragment = transformed.get("FRAGMENT");
            }
        }
        return original.call(name, vertex, geometry, tessControl, tessEval, fragment, vertexFormat, isFallback);
    }
}
