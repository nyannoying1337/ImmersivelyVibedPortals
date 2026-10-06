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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.objectweb.asm.Opcodes;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.ducks.IELevelExtractor_Invalidation;
import qouteall.imm_ptl.core.render.PortalViewRenderer;
import qouteall.imm_ptl.core.render.VisibleSectionDiscovery;

@Mixin(LevelExtractor.class)
public class MixinLevelExtractor implements IELevelExtractor_Invalidation {
    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    @Shadow
    private boolean shouldInvalidateCompiledGeometry;

    // the invalidation was postponed to the start of the next frame (see modifyShouldInvalidate)
    @Unique
    private boolean ip_invalidationPostponed = false;

    @Override
    public boolean ip_takePostponedInvalidation() {
        boolean postponed = ip_invalidationPostponed && shouldInvalidateCompiledGeometry;
        ip_invalidationPostponed = false;
        if (postponed) {
            shouldInvalidateCompiledGeometry = false;
        }
        return postponed;
    }

/**
     * Invalidating the compiled geometry recreates the renderer's buffers (with Sodium it waits for their last use).
     * When a portal view already used this renderer in this frame, its buffers are used by the frame being recorded,
     * and waiting for that crashed ("Cannot wait on a fence for the current submit"; e.g. the main view's renderer
     * after a view through a portal back into the player's dimension, right after a dimension change). So then the
     * request is postponed to the start of the next frame (DimensionRenderHelper.processPostponedInvalidation), before
     * the renderer is used, like vanilla does it. Not when the renderer has no ViewArea (it was just reset): then it
     * must be created now, and there is nothing in use.
     */
    @ModifyExpressionValue(
        method = "extract",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;shouldInvalidateCompiledGeometry:Z",
            opcode = Opcodes.GETFIELD
        )
    )
    private boolean modifyShouldInvalidate(boolean shouldInvalidate) {
        if (shouldInvalidate && levelRenderer.viewArea() != null && PortalViewRenderer.isUsedByViewThisFrame(levelRenderer)) {
            ip_invalidationPostponed = true;
            return false;
        }
        return shouldInvalidate;
    }

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
