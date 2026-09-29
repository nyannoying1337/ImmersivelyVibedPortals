package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FeatureRenderDispatcher.class)
public interface IEFeatureRenderDispatcher {
    @Accessor("preparedFrame")
    FeatureRenderDispatcher.PreparedFrame ip_getPreparedFrame();
}
