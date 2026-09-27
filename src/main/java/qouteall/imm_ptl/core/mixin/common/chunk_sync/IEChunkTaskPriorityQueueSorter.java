package qouteall.imm_ptl.core.mixin.common.chunk_sync;

import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * In 26.3 ChunkTaskPriorityQueueSorter was replaced by {@link ChunkTaskDispatcher}
 * and its ProcessorMailbox by {@link PriorityConsecutiveExecutor}.
 * (The class name is kept because it's referenced in the mixin config.)
 */
@Mixin(ChunkTaskDispatcher.class)
public interface IEChunkTaskPriorityQueueSorter {
    @Accessor("dispatcher")
    PriorityConsecutiveExecutor ip_getMailBox();
}
