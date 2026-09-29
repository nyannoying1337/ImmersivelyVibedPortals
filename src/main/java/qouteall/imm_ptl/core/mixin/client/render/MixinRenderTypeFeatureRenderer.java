package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.EntityClipping;

import java.util.List;

/**
 * Builds the submits of entities that are clipped by a portal with clipping vertex builders (see {@link EntityClipping}).
 */
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class MixinRenderTypeFeatureRenderer<Submit extends SubmitNode> {
    @Shadow
    protected abstract void buildGroup(FeatureFrameContext context, List<Submit> submits);

    /**
     * Build the group in runs of submits with the same clip planes.
     * (Every feature renderer builds its submits in list order.)
     */
    @Redirect(
        method = "prepareGroup",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/RenderTypeFeatureRenderer;buildGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;)V"
        )
    )
    private void redirectBuildGroup(RenderTypeFeatureRenderer<Submit> self, FeatureFrameContext context, List<Submit> submits) {
        if (!EntityClipping.hasClippedSubmits()) {
            buildGroup(context, submits);
            return;
        }

        int runStart = 0;
        float[] runPlanes = submits.isEmpty() ? null : EntityClipping.getSubmitPlanes(submits.get(0));
        for (int i = 1; i <= submits.size(); i++) {
            float[] planes = i < submits.size() ? EntityClipping.getSubmitPlanes(submits.get(i)) : null;
            if (i == submits.size() || planes != runPlanes) {
                EntityClipping.setCurrentBuildPlanes(runPlanes);
                buildGroup(context, submits.subList(runStart, i));
                EntityClipping.setCurrentBuildPlanes(null);
                runStart = i;
                runPlanes = planes;
            }
        }
    }

    @Inject(method = "getVertexBuilder", at = @At("HEAD"))
    private void onGetVertexBuilderBegin(RenderType renderType, CallbackInfoReturnable<VertexConsumer> cir) {
        EntityClipping.flushPending();
    }

    @Inject(method = "getVertexBuilder", at = @At("RETURN"), cancellable = true)
    private void onGetVertexBuilderEnd(RenderType renderType, CallbackInfoReturnable<VertexConsumer> cir) {
        VertexConsumer wrapped = EntityClipping.wrapVertexBuilder(renderType, cir.getReturnValue());
        if (wrapped != cir.getReturnValue()) {
            cir.setReturnValue(wrapped);
        }
    }
}
