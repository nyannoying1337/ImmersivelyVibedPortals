package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;

@Mixin(ScreenEffectRenderer.class)
public class MixinScreenEffectRenderer {
    // avoid rendering the in-wall (suffocation) overlay when colliding with portal
    @Inject(
        method = "submitBlockSprite",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onSubmitInWallOverlay(
        Identifier atlasLocation, float u0, float v0, float u1, float v1,
        PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int color,
        CallbackInfo ci
    ) {
        if (PortalRendering.isRendering()) {
            ci.cancel();
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            if (((IEEntity) player).ip_getCollidingPortal() != null) {
                ci.cancel();
                return;
            }
        }
        if (ClientTeleportationManager.isTeleportingFrequently()) {
            ci.cancel();
        }
    }
}
