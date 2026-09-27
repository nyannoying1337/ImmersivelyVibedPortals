package qouteall.imm_ptl.core.mixin.common.chunk_sync;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ThrottlingChunkTaskDispatcher;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Executor;

@Mixin(DistanceManager.class)
public interface IEDistanceManager {
    // in 26.3 the tickets are stored in TicketStorage instead of DistanceManager.tickets
    @Accessor("ticketStorage")
    TicketStorage ip_getTicketStorage();
    
    @Accessor("mainThreadExecutor")
    Executor ip_getMainThreadExecutor();
    
    @Accessor("ticketDispatcher")
    ThrottlingChunkTaskDispatcher ip_getTicketThrottler();
}
