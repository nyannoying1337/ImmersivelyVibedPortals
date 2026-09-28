package qouteall.imm_ptl.core.mixin.client.interaction;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

// named for historical reasons: pick() moved from GameRenderer to Minecraft in 26.x
@Mixin(Minecraft.class)
public class MixinGameRenderer_B {
    
    //do not update target when rendering portal
    @Inject(method = "pick(F)V", at = @At("HEAD"), cancellable = true)
    private void onUpdateTargetedEntity(float partialTick, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null) {
            if (WorldRenderInfo.isRendering()) {
                ci.cancel();
            }
        }
    }
    
    @Inject(method = "pick(F)V", at = @At("RETURN"))
    private void onUpdateTargetedEntityFinish(float partialTick, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null) {
            BlockManipulationClient.updatePointedBlock(partialTick);
        }
    }
}
