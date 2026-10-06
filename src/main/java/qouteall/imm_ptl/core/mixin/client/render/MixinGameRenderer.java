package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.profiling.Profiler;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.portal.animation.ClientPortalAnimationManagement;
import qouteall.imm_ptl.core.portal.animation.StableClientTimer;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.PortalViewRenderer;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer implements IEGameRenderer {
    @Shadow
    @Final
    @Mutable
    private Lightmap lightmap;

    @Shadow
    @Final
    private LightmapRenderStateExtractor lightmapRenderStateExtractor;

    @Shadow
    @Final
    @Mutable
    private FogRenderer fogRenderer;

    @Shadow
    @Final
    @Mutable
    private Camera mainCamera;

    @Shadow
    @Final
    private GlobalSettingsUniform globalSettingsUniform;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    protected abstract void extractCamera(DeltaTracker deltaTracker, float worldPartialTicks);

    @Shadow
    protected abstract void bobHurt(CameraRenderState cameraState, PoseStack poseStack);

    @Shadow
    protected abstract void bobView(CameraRenderState cameraState, PoseStack poseStack);

    @Unique
    private @Nullable RenderTarget ip_mainRenderTargetOverride;

    @Unique
    private static boolean ip_isRenderingHand = false;

    /**
     * In 26.3 the frame is update -> extract -> render.
     * Portal animation and teleportation must be handled before the camera is updated,
     * so that the extracted render state is consistent with the teleported player.
     */
    /**
     * False while a vanilla dimension change is being handled: in 26.3 ClientPacketListener.handleRespawn
     * renders a frame (the level loading screen) after the new level is set but before the player is moved
     * into it (e.g. leaving the End through the exit portal). ImmPtl's per-frame logic must not run then.
     */
    @Unique
    private boolean ip_isLevelStateConsistent() {
        return minecraft.level != null && minecraft.player != null && minecraft.player.level() == minecraft.level;
    }

    /**
     * Cross-portal view (third person camera behind a portal): replace the main level image with the
     * destination rendered by PortalViewRenderer, before the hand and HUD are drawn.
     */
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"
        )
    )
    private void onBeforeRender3dHud(CallbackInfo ci) {
        if (PortalViewRenderer.isRenderingPortalView()) {
            return;
        }
        com.mojang.blaze3d.pipeline.TextureTarget crossView = PortalViewRenderer.getCrossPortalViewTarget();
        if (crossView == null) {
            return;
        }
        RenderTarget main = ((GameRenderer) (Object) this).mainRenderTarget();
        int width = Math.min(crossView.width, main.width);
        int height = Math.min(crossView.height, main.height);
        var encoder = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(crossView.getColorTexture(), main.getColorTexture(), 0, 0, 0, 0, 0, width, height);
        encoder.copyTextureToTexture(crossView.getDepthTexture(), main.getDepthTexture(), 0, 0, 0, 0, 0, width, height);
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void onBeforeUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        Profiler.get().push("ip_pre_total_render");
        IPGlobal.PRE_TOTAL_RENDER_TASK_LIST.processTasks();
        Profiler.get().pop();
        if (!ip_isLevelStateConsistent()) {
            return;
        }
        Profiler.get().push("ip_pre_render");
        // Note do not use delta tick. use partial tick.
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        RenderStates.updatePreRenderInfo(partialTick);
        StableClientTimer.update(
            minecraft.level.getGameTime(), partialTick
        );
        ClientPortalAnimationManagement.update(); // must update before teleportation
        ClientTeleportationManager.manageTeleportation(false);
        IPGlobal.PRE_GAME_RENDER_EVENT.invoker().run();
        if (IPCGlobal.earlyRemoteUpload) {
            MyRenderHelper.earlyRemoteUpload();
        }
        Profiler.get().pop();

        RenderStates.frameIndex++;
    }

    /**
     * Portal views are rendered before the main view is extracted (see docs/rendering-26.3.md).
     * The main camera has been updated by {@link GameRenderer#update} at this point.
     */
    @Inject(method = "extract", at = @At("HEAD"))
    private void onBeforeExtract(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (minecraft.isGameLoadFinished() && advanceGameTime && ip_isLevelStateConsistent()) {
            Profiler.get().push("ip_portal_views");
            PortalViewRenderer.renderPortalViews(deltaTracker);
            Profiler.get().pop();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onAfterRender(CallbackInfo ci) {
        RenderStates.onTotalRenderEnd();


        if (IPCGlobal.lateClientLightUpdate) {
            Profiler.get().push("ip_late_update_light");
            MyRenderHelper.lateUpdateLight();
            Profiler.get().pop();
        }
    }

    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void onGetMainRenderTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (ip_mainRenderTargetOverride != null) {
            cir.setReturnValue(ip_mainRenderTargetOverride);
        }
    }

    // no hand/HUD in portal views
    @Inject(method = "render3dHud", at = @At("HEAD"), cancellable = true)
    private void onRender3dHud(CallbackInfo ci) {
        if (PortalViewRenderer.isRenderingPortalView()) {
            ci.cancel();
            return;
        }
        ip_isRenderingHand = true;
    }

    @Inject(method = "render3dHud", at = @At("RETURN"))
    private void onRender3dHudEnd(CallbackInfo ci) {
        ip_isRenderingHand = false;
    }

    //resize all world renderers when resizing window
    @Inject(method = "resize", at = @At("RETURN"))
    private void onResized(int width, int height, CallbackInfo ci) {
        ClientWorldLoader._onResize(width, height);
    }

    // the fog of a portal view is at the view's render distance (see PortalRenderer.getPortalRenderDistance)
    @ModifyArg(
        method = "extractCamera",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;"
        ),
        index = 1
    )
    private int modifyFogRenderDistance(int renderDistance) {
        if (PortalViewRenderer.isRenderingPortalView()) {
            return Math.min(renderDistance, qouteall.imm_ptl.core.render.context_management.WorldRenderInfo.getRenderDistance());
        }
        return renderDistance;
    }

    // not using ModifyArgs because ModifyArgs seems broken on Forge
    @ModifyArg(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
        index = 0
    )
    private float modifyBobViewTranslateX(float f) {
        return ip_isRenderingHand ? f : (float) (f * RenderStates.getViewBobbingOffsetMultiplier());
    }

    @ModifyArg(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
        index = 1
    )
    private float modifyBobViewTranslateY(float f) {
        return ip_isRenderingHand ? f : (float) (f * RenderStates.getViewBobbingOffsetMultiplier());
    }

    @ModifyArg(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
        index = 2
    )
    private float modifyBobViewTranslateZ(float f) {
        return ip_isRenderingHand ? f : (float) (f * RenderStates.getViewBobbingOffsetMultiplier());
    }

    @Override
    public Lightmap ip_getLightmap() {
        return lightmap;
    }

    @Override
    public void ip_setLightmap(Lightmap lightmap) {
        this.lightmap = lightmap;
    }

    @Override
    public LightmapRenderStateExtractor ip_getLightmapRenderStateExtractor() {
        return lightmapRenderStateExtractor;
    }

    @Override
    public FogRenderer ip_getFogRenderer() {
        return fogRenderer;
    }

    @Override
    public void ip_setFogRenderer(FogRenderer fogRenderer) {
        this.fogRenderer = fogRenderer;
    }

    @Override
    public void ip_setCamera(Camera camera) {
        this.mainCamera = camera;
    }

    @Override
    public GlobalSettingsUniform ip_getGlobalSettingsUniform() {
        return globalSettingsUniform;
    }

    @Override
    public void ip_setMainRenderTargetOverride(@Nullable RenderTarget target) {
        this.ip_mainRenderTargetOverride = target;
    }

    @Override
    public @Nullable RenderTarget ip_getMainRenderTargetOverride() {
        return ip_mainRenderTargetOverride;
    }
    
    @Override
    public void ip_extractCamera(DeltaTracker deltaTracker, float worldPartialTicks) {
        extractCamera(deltaTracker, worldPartialTicks);
    }

    @Override
    public void ip_applyViewBobbing(CameraRenderState cameraState, PoseStack poseStack) {
        bobHurt(cameraState, poseStack);
        if (minecraft.options.bobView().get()) {
            bobView(cameraState, poseStack);
        }
    }
}
