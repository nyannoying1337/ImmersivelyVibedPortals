package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.ForceMainThreadRebuild;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_ForceMainThreadRebuild {
    // in the frames that force main thread rebuild, compile the dirty visible sections synchronously
    @WrapOperation(
        method = "compileSections",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileAsync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V"
        )
    )
    private void wrapCompileAsync(
        SectionRenderDispatcher.RenderSection section, RenderSectionRegion region, Operation<Void> original
    ) {
        if (ForceMainThreadRebuild.isCurrentFrameForceMainThreadRebuild()) {
            section.compileSync(region);
        }
        else {
            original.call(section, region);
        }
    }
}
