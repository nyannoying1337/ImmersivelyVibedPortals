package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.PortalClipping;

import java.nio.ByteBuffer;

/**
 * The Projection uniform block has an extra vec4 (the portal clip plane), see {@link PortalClipping}.
 * Only the level projection buffer carries a real plane, the others write a disabled (zero) plane.
 */
@Mixin(ProjectionMatrixBuffer.class)
public class MixinProjectionMatrixBuffer {
    @Unique
    private static final int IP_EXTRA_SIZE = 16;
    
    @Unique
    private boolean ip_isLevelProjection = false;
    
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(String name, CallbackInfo ci) {
        ip_isLevelProjection = name.equals("level");
    }
    
    @ModifyExpressionValue(
        method = {"<init>", "writeBuffer"},
        at = @At(
            value = "FIELD",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;PROJECTION_MATRIX_UBO_SIZE:I"
        )
    )
    private int modifyUboSize(int original) {
        return original + IP_EXTRA_SIZE;
    }
    
    @WrapOperation(
        method = "writeBuffer",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/buffers/Std140Builder;get()Ljava/nio/ByteBuffer;"
        )
    )
    private ByteBuffer wrapBuild(Std140Builder builder, Operation<ByteBuffer> original) {
        if (ip_isLevelProjection) {
            builder.putVec4(PortalClipping.getCurrentPlane());
        }
        else {
            builder.putVec4(0, 0, 0, 0);
        }
        return original.call(builder);
    }
}
