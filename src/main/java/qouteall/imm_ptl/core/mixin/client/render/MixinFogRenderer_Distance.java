package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * Disables fog when the rendered view has no fog, and in fuse-view portal views.
 * (Replaces the 1.21.1 RenderSystem.setShaderFogStart/End hooks. In 26.3 the fog distances are in {@link FogData},
 * computed per view by the view's {@link FogRenderer} in GameRenderer.extractCamera.)
 */
@Mixin(FogRenderer.class)
public class MixinFogRenderer_Distance {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void onSetupFogReturn(
        Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker,
        float darkenWorldAmount, ClientLevel level,
        CallbackInfoReturnable<FogData> cir
    ) {
        if (!ip_shouldDisableFog()) {
            return;
        }

        // same as the vanilla "no fog" buffer (FogRenderer constructor)
        FogData fog = cir.getReturnValue();
        fog.environmentalStart = Float.MAX_VALUE;
        fog.environmentalEnd = Float.MAX_VALUE;
        fog.renderDistanceStart = Float.MAX_VALUE;
        fog.renderDistanceEnd = Float.MAX_VALUE;
        fog.skyEnd = Float.MAX_VALUE;
        fog.cloudEnd = Float.MAX_VALUE;
    }

    @Unique
    private static boolean ip_shouldDisableFog() {
        if (!WorldRenderInfo.isFogEnabled()) {
            return true;
        }

        // just disable fog for fuse-view portals for now
        // (non-fuse-view portals don't apply the scale transformation to the model view,
        // so the fog distance doesn't need to be transformed)
        return PortalRendering.isRendering() && PortalRendering.getRenderingPortal().isFuseView();
    }
}
