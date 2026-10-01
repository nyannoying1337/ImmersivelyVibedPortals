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
        // A portal view of another dimension collects the dirty sections around its camera, but the ViewArea grid
        // may not cover all of them (DimensionRenderHelper.selectForView), then the section is not in
        // the grid. It is reset (and compiled) when the grid moves over it.
        if (section == null) {
            return;
        }
        if (ForceMainThreadRebuild.isCurrentFrameForceMainThreadRebuild()) {
            section.compileSync(region);
        }
        else {
            original.call(section, region);
        }
    }

    @WrapOperation(
        method = "compileSections",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V"
        )
    )
    private void wrapCompileSync(
        SectionRenderDispatcher.RenderSection section, RenderSectionRegion region, Operation<Void> original
    ) {
        // see wrapCompileAsync
        if (section != null) {
            original.call(section, region);
        }
    }
}
