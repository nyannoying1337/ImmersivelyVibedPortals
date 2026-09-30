package qouteall.imm_ptl.core.mixin.client.accessor;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Future;

/**
 * Whether the graph is being rebuilt (then it may not match the camera yet). Used by PortalOcclusionCulling.
 */
@Mixin(SectionOcclusionGraph.class)
public interface IESectionOcclusionGraph {
    @Accessor("needsFullUpdate")
    boolean ip_needsFullUpdate();

    @Accessor("fullUpdateTask")
    @Nullable Future<?> ip_getFullUpdateTask();
}
