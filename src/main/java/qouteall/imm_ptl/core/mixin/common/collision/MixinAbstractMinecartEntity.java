package qouteall.imm_ptl.core.mixin.common.collision;

import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;

@Mixin(AbstractMinecart.class)
public class MixinAbstractMinecartEntity {
    // for debugging
    // in 26.x lerpTo is replaced by the InterpolationHandler,
    // onInterpolationStart is called when the interpolation target is set
    @Inject(
        method = "onInterpolationStart",
        at = @At("RETURN")
    )
    private void onUpdateTracketPositionAndAngles(
        InterpolationHandler interpolation, CallbackInfo ci
    ) {
        AbstractMinecart this_ = (AbstractMinecart) ((Object) this);
        if (!IPGlobal.allowClientEntityPosInterpolation) {
            PositionAndRotation target = interpolation.target();
            if (target != null) {
                this_.setPos(target.position());
            }
        }
    }
}
