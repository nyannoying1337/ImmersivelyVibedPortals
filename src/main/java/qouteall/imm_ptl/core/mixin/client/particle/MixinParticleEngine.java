package qouteall.imm_ptl.core.mixin.client.particle;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

@SuppressWarnings("resource")
@Mixin(ParticleEngine.class)
public class MixinParticleEngine implements IEParticleManager {
    @Shadow
    protected ClientLevel level;
    
    // skip particle rendering for far portals
    // (in 26.3 the particles are extracted into a render state instead of rendered directly)
    @Inject(
        method = "extract",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onBeginRenderParticles(
        ParticlesRenderState particlesRenderState, Frustum frustum, Camera camera, float partialTickTime,
        CallbackInfo ci
    ) {
        if (PortalRendering.isRendering()) {
            if (RenderStates.getRenderedPortalNum() > 4) {
                ci.cancel();
            }
        }
    }
    
    // the per-particle render filtering and the particle ticking dimension check moved to
    // MixinQuadParticleGroup and MixinParticleGroup, because in 26.3 they happen in ParticleGroup
    
    @Override
    public void ip_setWorld(ClientLevel world_) {
        level = world_;
    }
    
}
