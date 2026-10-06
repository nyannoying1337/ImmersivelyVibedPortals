package qouteall.imm_ptl.core.compat.mixin.entityculling;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.PortalViewRenderer;

/**
 * Entity Culling hides entities that its ray tracing from the player's camera finds behind blocks.
 * - Portals (and mirrors) are entities, but their surface can be seen where the ray tracing of their box says
 *   it can't: they disappeared.
 * - In a portal view the camera is somewhere else (often another dimension), so the culling result of the
 *   player's camera doesn't apply: entities seen through portals disappeared.
 * So portals are never culled, and nothing is culled while a portal view is rendered.
 * (NMSCullingHelper.ignoresCulling is checked before an entity's culled flag is applied.)
 */
@Pseudo
@Mixin(targets = "dev.tr7zw.entityculling.NMSCullingHelper", remap = false)
public class MixinEntityCullingHelper {
    @Inject(method = "ignoresCulling", at = @At("HEAD"), cancellable = true)
    private static void onIgnoresCulling(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Portal || PortalViewRenderer.isRenderingPortalView()) {
            cir.setReturnValue(true);
        }
    }
}
