package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * SkyRenderer keeps the main target it was created with.
 * Use the current target instead, so that the sky is drawn into portal view targets.
 */
@Mixin(SkyRenderer.class)
public class MixinSkyRenderer_Target {
    @Redirect(
        method = "render",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;"
        )
    )
    private RenderTarget redirectRenderTarget(SkyRenderer instance) {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }
}
