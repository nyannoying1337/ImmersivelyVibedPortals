package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.mc_utils.WireRenderingHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Environment(EnvType.CLIENT)
public class PortalEntityRenderer extends EntityRenderer<Portal, PortalEntityRenderer.PortalRenderState> {

    public static class PortalRenderState extends EntityRenderState {
        public @Nullable Portal portal;
    }

    public PortalEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public PortalRenderState createRenderState() {
        return new PortalRenderState();
    }

    @Override
    public void extractRenderState(Portal portal, PortalRenderState state, float partialTicks) {
        super.extractRenderState(portal, state, partialTicks);
        state.portal = portal;
    }

    @Override
    public boolean shouldRender(Portal portal, Frustum culler, double camX, double camY, double camZ, float partialTicks) {
        // which portals are visible is decided by PortalViewRenderer;
        // a portal without a rendered view draws nothing
        return true;
    }

    @Override
    public void submit(
        PortalRenderState state, PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector, CameraRenderState camera
    ) {
        Portal portal = state.portal;
        if (portal != null) {
            PortalSurfaceRendering.submitPortalSurface(portal, poseStack, submitNodeCollector);

            if (OverlayRendering.shouldRenderOverlay(portal)) {
                OverlayRendering.submitOverlay(portal, poseStack, submitNodeCollector);
            }

            if (IPGlobal.debugRenderPortalShapeMesh && !PortalRendering.isRendering()) {
                submitNodeCollector.submitCustomGeometry(
                    poseStack, RenderTypes.lines(),
                    (pose, buffer) -> {
                        PoseStack lineStack = new PoseStack();
                        lineStack.last().set(pose);
                        WireRenderingHelper.renderPortalShapeMeshDebug(lineStack, buffer, portal);
                    }
                );
            }
        }

        super.submit(state, poseStack, submitNodeCollector, camera);
    }
}
