package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;

@Mixin(LevelExtractor.class)
public class MixinLevelExtractor_CrossPortalEntity {
    // add the projections of the entities that are halfway through a portal leading into this dimension
    @Inject(method = "extractVisibleEntities", at = @At("TAIL"))
    private void onExtractVisibleEntitiesEnd(
        Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo ci
    ) {
        CrossPortalEntityRenderer.extractEntityProjections(camera, deltaTracker, output);
        
        // global portals (dimension stacks, world wrapping) are not in the level's entity list
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            for (Portal portal : GlobalPortalStorage.getGlobalPortals(level)) {
                float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true);
                output.entityRenderStates.add(
                    Minecraft.getInstance().levelRenderer.entityRenderDispatcher().extractEntity(portal, partialTicks)
                );
            }
        }
    }
}
