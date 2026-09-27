package qouteall.imm_ptl.peripheral.mixin.client.portal_wand;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.peripheral.wand.PortalWandItem;

@Mixin(DebugRenderer.class)
public class MixinDebugRenderer {
    // let's put portal wand marking render into debug renderer
    // in 26.3, the debug renderer emits gizmos (a gizmo collector is active during emitGizmos),
    // and the wand rendering emits its lines as gizmos
    @Inject(
        method = "emitGizmos",
        at = @At("RETURN")
    )
    private void onEmitGizmos(
        Frustum frustum,
        double camX, double camY, double camZ,
        float partialTicks,
        CallbackInfo ci
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        ItemStack itemStack = player.getMainHandItem();

        if (itemStack.getItem() == PortalWandItem.instance) {
            PortalWandItem.clientRender(player, itemStack, new PoseStack(), camX, camY, camZ);
        }
    }

}
