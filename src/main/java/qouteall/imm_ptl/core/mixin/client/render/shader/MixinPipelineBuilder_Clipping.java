package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.PortalClipping;

/**
 * Adds portal front clipping to world shaders when a pipeline is compiled.
 * Done per pipeline, because the fragment shader may only read the clip distance
 * if the pipeline's vertex shader writes it.
 */
@Mixin(PipelineBuilder.class)
public class MixinPipelineBuilder_Clipping {
    @WrapOperation(
        method = "generateBackendCreateInfo",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/frontend/shaders/PipelineBuilder;loadShaderSource(Lnet/minecraft/resources/Identifier;Lcom/mojang/renderpearl/api/pipeline/ShaderType;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;)Ljava/lang/String;"
        )
    )
    private String wrapLoadShaderSource(
        Identifier id, ShaderType type, ShaderSource shaderSource, Operation<String> original,
        @Local(argsOnly = true) RenderPipeline pipeline
    ) {
        String source = original.call(id, type, shaderSource);
        if (source == null) {
            return null;
        }
        
        Identifier vertexId = pipeline.getShaders().get(ShaderType.VERTEX);
        if (vertexId == null) {
            return source;
        }
        
        if (type == ShaderType.VERTEX) {
            if (PortalClipping.shouldTransformVertexShader(vertexId, source)) {
                return PortalClipping.transformVertexShader(source);
            }
        }
        else if (type == ShaderType.FRAGMENT) {
            String vertexSource = shaderSource.getShader(vertexId, ShaderType.VERTEX);
            if (PortalClipping.shouldTransformVertexShader(vertexId, vertexSource)) {
                return PortalClipping.transformFragmentShader(source);
            }
        }
        return source;
    }
}
