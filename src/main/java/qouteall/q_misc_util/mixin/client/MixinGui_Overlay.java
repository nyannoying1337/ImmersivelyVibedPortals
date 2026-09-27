package qouteall.q_misc_util.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.q_misc_util.CustomTextOverlay;

@Mixin(Hud.class)
public class MixinGui_Overlay {
    @Shadow
    @Final
    private Minecraft minecraft;
    
    @Inject(
        method = "extractRenderState", at = @At("RETURN")
    )
    private void onRender(
        GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci
    ) {
        if (!((Hud) (Object) this).isHidden()) {
            CustomTextOverlay.render(guiGraphics, deltaTracker);
        }
    }
}
