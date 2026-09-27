package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import qouteall.imm_ptl.core.portal.LoadingIndicatorEntity;

/**
 * The loading indicator entity is invisible (it doesn't even show its name tag).
 */
public class LoadingIndicatorRenderer extends EntityRenderer<LoadingIndicatorEntity, EntityRenderState> {
    public LoadingIndicatorRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }

    @Override
    public void submit(
        EntityRenderState state, PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector, CameraRenderState camera
    ) {
        // render nothing
    }
}
