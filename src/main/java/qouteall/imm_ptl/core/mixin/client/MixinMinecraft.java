package qouteall.imm_ptl.core.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.util.profiling.Profiler;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.miscellaneous.ClientPerformanceMonitor;
import qouteall.imm_ptl.core.portal.animation.ClientPortalAnimationManagement;
import qouteall.imm_ptl.core.portal.animation.StableClientTimer;
import qouteall.imm_ptl.core.render.PortalViewRenderer;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;


@Mixin(Minecraft.class)
public abstract class MixinMinecraft implements IEMinecraftClient {
    @Mutable
    @Shadow
    @Final
    public LevelRenderer levelRenderer;
    
    @Mutable
    @Shadow
    @Final
    public LevelExtractor levelExtractor;
    
    @Shadow
    private static int fps;
    
    @Shadow
    @Nullable
    public ClientLevel level;
    
    @Shadow
    @Final
    private static Logger LOGGER;
    
    @Shadow
    private Thread gameThread;
    
    @WrapOperation(
        method = "Lnet/minecraft/client/Minecraft;run()V",
        at = @At(
            value = "INVOKE",
            target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"
        )
    )
    private Thread testMixinExtra(Operation<Thread> original) {
        LOGGER.info("[ImmPtl] MixinExtra is working!");
        return original.call();
    }
    
    /**
     * The whole process involving portal animation and teleportation:
     * - begin ticking
     * <p>
     * - tick entities:
     * - set last tick pos to current pos
     * - do collision calculation for movements, update current pos
     * - portal.animation.lastTickAnimatedState = thisTickAnimatedState, thisTickAnimatedState = null
     * - increase game time
     * - end ticking
     * - partialTick should be 0
     * - update portal animation (set thisTickAnimatedState as 1 tick later)
     * - manage teleportation (right after updating portal animation)
     * - rendering (interpolate between last tick pos and current pos)
     * Note: the camera position is always behind the current tick position
     * - partialTick should be 1
     * - loop
     */
    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V"
        )
    )
    private void onBeforeTickingEntities(CallbackInfo ci) {
//        RenderStates.tickDelta = 1;
//        StableClientTimer.update(level.getGameTime(), RenderStates.tickDelta);
//        ClientPortalAnimationManagement.update();
//        IPCGlobal.clientTeleportationManager.manageTeleportation(true);
    }
    
    // this happens after ticking client world and entities
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;tick()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterClientTick(CallbackInfo ci) {
        Profiler.get().push("imm_ptl_client_tick");
        
        // including ticking remote worlds
        ClientWorldLoader.tick();
        
        RenderStates.setPartialTick(0);
        StableClientTimer.tick();
        StableClientTimer.update(level.getGameTime(), RenderStates.getPartialTick());
        ClientPortalAnimationManagement.tick(); // must be after remote world ticking
        ClientTeleportationManager.manageTeleportation(true);
        
        IPGlobal.POST_CLIENT_TICK_EVENT.invoker().run();
        
        Profiler.get().pop();
    }
    
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;fps:I",
            opcode = Opcodes.PUTSTATIC,
            shift = At.Shift.AFTER
        )
    )
    private void onSnooperUpdate(boolean tick, CallbackInfo ci) {
        ClientPerformanceMonitor.updateEverySecond(fps);
    }
    
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V",
        at = @At("HEAD")
    )
    private void onSetWorld(ClientLevel clientLevel, boolean stopSound, CallbackInfo ci) {
        if (ClientWorldLoader.getIsInitialized()) {
            LOGGER.info("Client cleanup");
            IPCGlobal.CLIENT_CLEANUP_EVENT.invoker().run();
            
            if (clientLevel == null) {
                LOGGER.info("Client exit world");
                IPCGlobal.CLIENT_EXIT_EVENT.invoker().run();
            }
            
            ClientWorldLoader.cleanUp();
        }
        else {
            LOGGER.info("Client world updated but not counted as cleanup");
        }
    }
    
    // vanilla only ends the frame of its current LevelRenderer
    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;endFrame()V",
            shift = At.Shift.AFTER
        )
    )
    private void onEndFrame(boolean advanceGameTime, CallbackInfo ci) {
        ClientWorldLoader._onEndFrame();
        PortalViewRenderer.onEndFrame();
    }
    
    @Override
    public void ip_setLevelRendererAndExtractor(LevelRenderer levelRenderer, LevelExtractor levelExtractor) {
        this.levelRenderer = levelRenderer;
        this.levelExtractor = levelExtractor;
    }
    
    @Override
    public Thread ip_getRunningThread() {
        return gameThread;
    }
}
