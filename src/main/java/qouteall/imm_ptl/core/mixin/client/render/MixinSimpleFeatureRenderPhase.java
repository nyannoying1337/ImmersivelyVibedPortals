package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.EntityClipping;

@Mixin(SimpleFeatureRenderPhase.class)
public class MixinSimpleFeatureRenderPhase {
    // tag the submits of entities that are clipped by a portal (see EntityClipping)
    @Inject(method = "submit", at = @At("HEAD"))
    private void onSubmit(SubmitNode submit, CallbackInfo ci) {
        EntityClipping.onSubmit(submit);
    }
}
