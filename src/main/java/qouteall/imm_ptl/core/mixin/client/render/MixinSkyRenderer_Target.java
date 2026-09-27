package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * SkyRenderer keeps the main target it was created with.
 * Use the current target instead, so that the sky is drawn into portal view targets.
 * Class is named for its first job; it also fixes the camera height for portal views.
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

    // the dark sky disc below the horizon depends on the camera height, which is different in portal views
    @Redirect(
        method = "shouldRenderDarkDisc",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 redirectEyePosition(LocalPlayer player, float partialTick) {
        if (WorldRenderInfo.isRendering()) {
            return WorldRenderInfo.getCameraPos();
        }
        return player.getEyePosition(partialTick);
    }
}
