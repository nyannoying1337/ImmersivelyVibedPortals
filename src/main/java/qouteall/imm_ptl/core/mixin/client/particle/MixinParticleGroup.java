package qouteall.imm_ptl.core.mixin.client.particle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// in 26.3 the particle ticking moved from ParticleEngine to ParticleGroup
@Mixin(ParticleGroup.class)
public class MixinParticleGroup {
    // a lava ember particle can generate a smoke particle during ticking
    // avoid generating the particle into the wrong dimension
    @Inject(
        method = "Lnet/minecraft/client/particle/ParticleGroup;tickParticle(Lnet/minecraft/client/particle/Particle;)V",
        at = @At("HEAD"), cancellable = true
    )
    private void onTickParticle(Particle particle, CallbackInfo ci) {
        if (((IEParticle) particle).portal_getWorld() != Minecraft.getInstance().level) {
            ci.cancel();
        }
    }
}
