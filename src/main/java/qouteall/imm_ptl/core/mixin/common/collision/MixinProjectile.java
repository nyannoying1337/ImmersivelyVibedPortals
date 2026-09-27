package qouteall.imm_ptl.core.mixin.common.collision;

import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Projectile.class)
public abstract class MixinProjectile extends MixinEntity {
    
    // make it recognize the owner in another dimension:
    // In 26.x vanilla already does it. Projectile.getOwner() uses EntityReference.getEntity(owner, level)
    // which uses ServerLevel.getEntityInAnyDimension(UUID), which searches all levels.
    // So the old redirect (of ServerLevel.getEntity(UUID) in getOwner) is no longer needed.

//    @Shadow
//    public abstract void onHit(HitResult hitResult);
//
//    @Inject(method = "Lnet/minecraft/world/entity/projectile/Projectile;onHit(Lnet/minecraft/world/phys/HitResult;)V", at = @At(value = "HEAD"), cancellable = true)
//    protected void onHit(HitResult hitResult, CallbackInfo ci) {
//        Entity this_ = (Entity) (Object) this;
//        if (hitResult instanceof BlockHitResult) {
//            Block hittingBlock = this_.level().getBlockState(((BlockHitResult) hitResult).getBlockPos()).getBlock();
//            if (hitResult.getType() == HitResult.Type.BLOCK &&
//                hittingBlock == PortalPlaceholderBlock.instance
//            ) {
//                ci.cancel();
//            }
//        }
//    }
//
    
}