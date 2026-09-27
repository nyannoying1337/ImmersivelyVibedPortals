package qouteall.imm_ptl.core.mixin.common.chunk_sync;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.chunk_loading.PlayerChunkLoading;
import qouteall.imm_ptl.core.ducks.IEChunkMap;

import java.util.Set;

@Mixin(value = ChunkMap.class, priority = 1100)
public abstract class MixinChunkMap_C implements IEChunkMap {
    
    @Shadow
    @Final
    private ServerLevel level;
    
    @Shadow
    protected abstract ChunkHolder getVisibleChunkIfPresent(long long_1);
    
    @Shadow
    @Final
    private ThreadedLevelLightEngine lightEngine;
    
    @Shadow
    abstract int getPlayerViewDistance(ServerPlayer serverPlayer);
    
    @Override
    public int ip_getPlayerViewDistance(ServerPlayer player) {
        return getPlayerViewDistance(player);
    }
    
    @Override
    public ServerLevel ip_getWorld() {
        return level;
    }
    
    @Override
    public ThreadedLevelLightEngine ip_getLightingProvider() {
        return lightEngine;
    }
    
    @Override
    public ChunkHolder ip_getChunkHolder(long chunkPosLong) {
        return getVisibleChunkIfPresent(chunkPosLong);
    }
    
    /**
     * packets will be sent on {@link PlayerChunkLoading}
     */
    @Inject(
        method = "applyChunkTrackingView",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onUpdateChunkTracking(
        ServerPlayer serverPlayer, ChunkTrackingView chunkTrackingView, CallbackInfo ci
    ) {
        ci.cancel();
    }
    
    /**
     * In 26.3 this method also notifies the chunk source and debug synchronizers,
     * so instead of overwriting it, only the vanilla per-player sending is disabled
     * (see {@link #redirectGetAllPlayersOnChunkReadyToSend(PlayerMap)}).
     */
    @Inject(
        method = "onChunkReadyToSend",
        at = @At("HEAD")
    )
    private void onOnChunkReadyToSend(ChunkHolder chunkHolder, LevelChunk chunk, CallbackInfo ci) {
        ImmPtlChunkTracking.onChunkProvidedDeferred(chunk);
    }

    @Redirect(
        method = "onChunkReadyToSend",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/PlayerMap;getAllPlayers()Ljava/util/Set;"
        )
    )
    private Set<ServerPlayer> redirectGetAllPlayersOnChunkReadyToSend(PlayerMap playerMap) {
        return Set.of();
    }
}
