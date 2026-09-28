package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.portal.Portal;

// In 26.3 client position interpolation is done by an InterpolationHandler,
// which calls Entity.onInterpolationStart when it gets a new target.
@Mixin(Entity.class)
public class MixinLivingEntity_C {
    // avoid entity position interpolate when crossing portal to the same dimension
    @Inject(method = "onInterpolationStart", at = @At("HEAD"))
    private void onInterpolationStart(InterpolationHandler interpolation, CallbackInfo ci) {
        if (!((Object) this instanceof LivingEntity this_)) {
            return;
        }
        if (!this_.level().isClientSide()) {
            return;
        }
        PositionAndRotation target = interpolation.target();
        if (target == null) {
            return;
        }
        Vec3 targetPos = target.position();
        
        if (!IPGlobal.allowClientEntityPosInterpolation) {
            this_.setPos(targetPos);
            return;
        }
        
        Portal collidingPortal = ((IEEntity) this_).ip_getCollidingPortal();
        if (collidingPortal != null) {
            if (this_.position().distanceToSqr(targetPos) > 4) {
                McHelper.setPosAndLastTickPos(
                    this_,
                    targetPos,
                    targetPos.subtract(McHelper.getWorldVelocity(this_))
                );
                McHelper.updateBoundingBox(this_);
            }
        }
    }
}
