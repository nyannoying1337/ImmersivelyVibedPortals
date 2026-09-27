package qouteall.imm_ptl.core.mixin.client.particle;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

// in 26.3 the particles are extracted by the particle groups instead of rendered by ParticleEngine
// TODO(26.3): the other particle groups (item pickup, elder guardian) are not filtered
@Mixin(QuadParticleGroup.class)
public class MixinQuadParticleGroup {
    // maybe incompatible with sodium and iris
    @WrapWithCondition(
        method = "extractRenderState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/SingleQuadParticle;extract(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lnet/minecraft/client/Camera;F)V"
        )
    )
    private boolean redirectBuildGeometry(
        SingleQuadParticle instance, QuadParticleRenderState renderState, Camera camera, float partialTick
    ) {
        return RenderStates.shouldRenderParticle(instance);
    }
}
