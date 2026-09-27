package qouteall.imm_ptl.core.mixin.client.multiworld_awareness;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.FogRendererContext;

@Mixin(value = FogRenderer.class, priority = 1100)
public class MixinFogRenderer {
    // TODO(26.3): FogRenderer no longer keeps the fog state in static fields
    //  (fogRed, fogGreen, fogBlue, targetBiomeFog, previousBiomeFog, biomeChangedTime were removed).
    //  The fog color is recomputed every frame in computeFogColor(), so there is no per-dimension static state
    //  to swap anymore. The context swapping hooks are no-ops, and the current fog color is captured
    //  from the last computeFogColor() call.
    @Unique
    private static Vec3 ip_lastFogColor = Vec3.ZERO;

    static {
        FogRendererContext.copyContextFromObject = context -> {
        };

        FogRendererContext.copyContextToObject = context -> {
        };

        FogRendererContext.getCurrentFogColor = () -> ip_lastFogColor;

        FogRendererContext.init();
    }

    @Inject(
        method = "computeFogColor",
        at = @At("RETURN")
    )
    private void onComputeFogColorReturn(
        Camera camera, float partialTicks, ClientLevel level, int renderDistance,
        float darkenWorldAmount, Vector4f dest, CallbackInfo ci
    ) {
        ip_lastFogColor = new Vec3(dest.x(), dest.y(), dest.z());
    }
}
