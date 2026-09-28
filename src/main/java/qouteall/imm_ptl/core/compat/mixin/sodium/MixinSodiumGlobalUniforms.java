package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.Std140Builder;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumGlobalUniforms;
import qouteall.imm_ptl.core.render.PortalClipping;

import java.nio.ByteBuffer;

/**
 * Sodium's terrain uniform block gets the portal clip plane appended (see PortalClipping).
 * The block has room for it (Sodium allocates 256 bytes per instance, the data takes 192).
 * <p>
 * The plane is captured when the uniforms are created and is part of their equality:
 * DynamicGpuDataStorageMapped reuses the previous slot when the new data equals the previous data,
 * and views that differ only by their clip plane (e.g. a mirror and a portal seen inside it) would
 * otherwise share one plane.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager$GlobalUniforms", remap = false)
public class MixinSodiumGlobalUniforms implements IESodiumGlobalUniforms {
    @Unique
    private Vector4f ip_clipPlane;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        ip_clipPlane = new Vector4f(PortalClipping.getCurrentPlane());
    }

    @Override
    public Vector4f ip_getClipPlane() {
        return ip_clipPlane;
    }

    @Inject(method = "equals", at = @At("HEAD"), cancellable = true)
    private void onEquals(Object other, CallbackInfoReturnable<Boolean> cir) {
        if (other instanceof IESodiumGlobalUniforms otherUniforms
            && !ip_clipPlane.equals(otherUniforms.ip_getClipPlane())
        ) {
            cir.setReturnValue(false);
        }
    }

    @WrapOperation(
        method = "write",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/buffers/Std140Builder;get()Ljava/nio/ByteBuffer;"
        )
    )
    private ByteBuffer wrapGet(Std140Builder builder, Operation<ByteBuffer> original) {
        builder.putVec4(ip_clipPlane);
        return original.call(builder);
    }
}
