package qouteall.imm_ptl.core.mixin.common.container_gui;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(ContainerOpenersCounter.class)
public abstract class MixinContainerOpenersCounter {
    @Shadow
    protected abstract boolean hasContainerOpen(Entity entity, BlockPos blockPos);
    
    // Vanilla only looks for users near the container.
    // The container could be opened via portal, the player could be anywhere in any dimension,
    // so check all players (non-player users are still found by vanilla's area search).
    @Inject(method = "getEntitiesWithContainerOpen", at = @At("RETURN"), cancellable = true)
    private void onGetEntitiesWithContainerOpen(
        Level level, BlockPos pos, CallbackInfoReturnable<List<ContainerUser>> cir
    ) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        
        List<ContainerUser> result = new ArrayList<>(cir.getReturnValue());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!result.contains(player) && hasContainerOpen(player, pos)) {
                result.add(player);
            }
        }
        cir.setReturnValue(result);
    }
}
