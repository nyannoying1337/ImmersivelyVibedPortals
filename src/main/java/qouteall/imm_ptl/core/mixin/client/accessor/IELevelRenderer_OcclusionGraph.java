package qouteall.imm_ptl.core.mixin.client.accessor;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The real graph (LevelRenderer.sectionOcclusionGraph() can return the portal view graph, see MixinLevelRenderer).
 * Used by PortalOcclusionCulling.
 */
@Mixin(LevelRenderer.class)
public interface IELevelRenderer_OcclusionGraph {
    @Accessor("sectionOcclusionGraph")
    SectionOcclusionGraph ip_getRealSectionOcclusionGraph();
}
