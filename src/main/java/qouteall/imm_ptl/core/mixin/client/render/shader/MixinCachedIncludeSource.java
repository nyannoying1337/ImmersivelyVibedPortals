package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.render.PortalClipping;

@Mixin(ShaderSource.CachedIncludeSource.class)
public class MixinCachedIncludeSource {
    // add the portal clip plane to the Projection uniform block
    @ModifyVariable(method = "create", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String modifyIncludeSource(String source, Identifier id) {
        if (id.getNamespace().equals("minecraft") && id.getPath().endsWith("projection.glsl")) {
            return PortalClipping.transformProjectionInclude(source);
        }
        // Sodium's terrain uniforms, see MixinSodiumGlobalUniforms
        if (id.getNamespace().equals("sodium") && id.getPath().endsWith("globals.glsl")) {
            return PortalClipping.transformSodiumGlobalsInclude(source);
        }
        return source;
    }
}
