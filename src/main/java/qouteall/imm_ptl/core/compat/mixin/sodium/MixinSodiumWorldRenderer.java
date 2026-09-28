package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.trigger.CameraMovement;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.PortalViewRenderer;

/**
 * A portal view's camera must not count as a camera movement of the renderer:
 * the renderer is shared with the main view (and other views) of the same dimension, and the main view
 * would otherwise see its camera "move" twice per frame (invalidating its culling results and
 * re-triggering translucency sorting every frame). See also {@link MixinSodiumRenderSectionManager}.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer {
    @Shadow
    private Vector3d lastCameraPos;
    @Shadow
    private double lastCameraPitch;
    @Shadow
    private double lastCameraYaw;
    @Shadow
    private Matrix4f cullMatrix;

    @Unique
    private @Nullable Vector3d ip_savedCameraPos;
    @Unique
    private double ip_savedCameraPitch;
    @Unique
    private double ip_savedCameraYaw;
    @Unique
    private @Nullable Matrix4f ip_savedCullMatrix;

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void onSetupTerrainBegin(
        Camera camera, Viewport viewport, FogParameters fogParameters, boolean useOcclusionCulling,
        boolean updateChunksImmediately, Matrix4f cullMatrix, CallbackInfo ci
    ) {
        if (!PortalViewRenderer.isRenderingPortalView()) {
            return;
        }
        ip_savedCameraPos = lastCameraPos;
        ip_savedCameraPitch = lastCameraPitch;
        ip_savedCameraYaw = lastCameraYaw;
        ip_savedCullMatrix = this.cullMatrix == null ? null : new Matrix4f(this.cullMatrix);
    }

    @Inject(method = "setupTerrain", at = @At("RETURN"))
    private void onSetupTerrainEnd(
        Camera camera, Viewport viewport, FogParameters fogParameters, boolean useOcclusionCulling,
        boolean updateChunksImmediately, Matrix4f cullMatrix, CallbackInfo ci
    ) {
        if (!PortalViewRenderer.isRenderingPortalView()) {
            return;
        }
        lastCameraPos = ip_savedCameraPos;
        lastCameraPitch = ip_savedCameraPitch;
        lastCameraYaw = ip_savedCameraYaw;
        if (ip_savedCullMatrix == null) {
            this.cullMatrix = null;
        }
        else {
            this.cullMatrix.set(ip_savedCullMatrix);
        }
    }

    @WrapOperation(
        method = "setupTerrain",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;notifyChangedCamera()V"
        )
    )
    private void wrapNotifyChangedCamera(RenderSectionManager instance, Operation<Void> original) {
        if (!PortalViewRenderer.isRenderingPortalView()) {
            original.call(instance);
        }
    }

    @WrapOperation(
        method = "setupTerrain",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;processGFNIMovement(Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/trigger/CameraMovement;)V"
        )
    )
    private void wrapProcessGFNIMovement(
        RenderSectionManager instance, CameraMovement movement, Operation<Void> original
    ) {
        if (!PortalViewRenderer.isRenderingPortalView()) {
            original.call(instance, movement);
        }
    }
}
