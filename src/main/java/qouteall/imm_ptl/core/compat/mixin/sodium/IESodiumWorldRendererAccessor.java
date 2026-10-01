package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// for the terrain diagnostics of portal views (OnSodiumPresent.getViewSectionStats)
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface IESodiumWorldRendererAccessor {
    @Accessor("renderSectionManager")
    RenderSectionManager ip_getRenderSectionManager();
}
