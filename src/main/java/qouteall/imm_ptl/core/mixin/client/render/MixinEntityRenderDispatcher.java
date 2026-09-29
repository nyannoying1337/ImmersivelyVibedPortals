package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.EntityClipping;

@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {
    // called by LevelExtractor.isEntityVisible when extracting the entities of a view
    @Inject(
        method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onShouldRenderEntity(
        Entity entity, Frustum frustum,
        double camX, double camY, double camZ, float partialTicks,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!CrossPortalEntityRenderer.shouldRenderEntityNow(entity)) {
            cir.setReturnValue(false);
        }
    }

    // apply the portal rotation/scaling to the projection of an entity that is halfway through a portal
    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
            ordinal = 0,
            shift = At.Shift.AFTER
        )
    )
    private void onSubmitEntityTranslated(
        EntityRenderState renderState, CameraRenderState camera,
        double x, double y, double z,
        PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
        CallbackInfo ci
    ) {
        CrossPortalEntityRenderer.applyProjectionTransformation(renderState, poseStack);
    }
    // entities halfway through a portal are clipped by the portal plane (see EntityClipping)
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void onExtractEntity(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        CrossPortalEntityRenderer.onEntityExtracted(entity, cir.getReturnValue());
    }

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At("HEAD")
    )
    private void onSubmitEntityBegin(
        EntityRenderState renderState, CameraRenderState camera,
        double x, double y, double z,
        PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
        CallbackInfo ci
    ) {
        EntityClipping.onBeginSubmitEntity(renderState, camera.pos);
    }

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At("RETURN")
    )
    private void onSubmitEntityEnd(CallbackInfo ci) {
        EntityClipping.onEndSubmitEntity();
    }
}
