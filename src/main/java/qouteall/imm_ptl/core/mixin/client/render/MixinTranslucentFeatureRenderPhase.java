package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.EntityClipping;

@Mixin(TranslucentFeatureRenderPhase.class)
public class MixinTranslucentFeatureRenderPhase {
    // tag the submits of entities that are clipped by a portal (see EntityClipping)
    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/feature/submit/TranslucentSubmit;)V",
        at = @At("HEAD")
    )
    private void onSubmit(TranslucentSubmit submit, CallbackInfo ci) {
        EntityClipping.onSubmit(submit);
    }
}
