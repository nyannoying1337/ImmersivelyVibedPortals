package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.ducks.IECloudRenderer;

import java.util.Optional;

@Mixin(CloudRenderer.class)
public abstract class MixinCloudRenderer implements IECloudRenderer {
    @Shadow
    protected abstract Optional<CloudRenderer.TextureData> prepare(ResourceManager manager, ProfilerFiller profiler);
    
    @Shadow
    protected abstract void apply(Optional<CloudRenderer.TextureData> preparations, ResourceManager manager, ProfilerFiller profiler);
    
    // vanilla only registers the first LevelRenderer's cloud renderer as a reload listener
    @Override
    public void ip_reloadNow(ResourceManager resourceManager) {
        apply(prepare(resourceManager, InactiveProfiler.INSTANCE), resourceManager, InactiveProfiler.INSTANCE);
    }
}
