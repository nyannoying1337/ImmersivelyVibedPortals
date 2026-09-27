package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.core.SectionPos;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.render.VisibleSectionDiscovery;

@Mixin(LevelExtractor.class)
public class MixinLevelExtractor {
    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    /**
     * A portal view into the main view's dimension uses the main LevelRenderer/LevelExtractor.
     * Don't move the section update tracker to the portal camera (and back every frame),
     * which would mark the grid edges dirty and force recompiles.
     */
    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/SectionUpdateTracker;repositionCamera(Lnet/minecraft/core/SectionPos;)V"
        )
    )
    private void wrapRepositionCamera(
        SectionUpdateTracker instance, SectionPos cameraSectionPos, Operation<Void> original
    ) {
        if (VisibleSectionDiscovery.isRenderingPortalViewWithMainLevelRenderer(levelRenderer)) {
            return;
        }
        original.call(instance, cameraSectionPos);
    }
    
    // vanilla only notifies its current extractor, apply to the other dimensions too
    @Inject(method = "allChanged", at = @At("RETURN"))
    private void onAllChanged(CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().levelExtractor) {
            ClientWorldLoader._onCurrentExtractorAllChanged();
        }
    }
    
    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void onResourceReload(ResourceManager resourceManager, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().levelExtractor) {
            ClientWorldLoader._onResourceReload();
        }
    }
}
