package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a portal's surface, textured with the view that was rendered for it
 * (see {@link PortalViewRenderer} and docs/rendering-26.3.md).
 * <p>
 * Each pooled view target is registered as a texture {@code immersive_portals:portal_view/N}
 * and has its own render type, so the surface can be submitted like normal entity geometry.
 */
public class PortalSurfaceRendering {
    public static final RenderPipeline PORTAL_VIEW_PIPELINE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("immersive_portals", "pipeline/portal_view"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withVertexShader(Identifier.fromNamespaceAndPath("immersive_portals", "core/portal_view"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("immersive_portals", "core/portal_view"))
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexBinding(0, DefaultVertexFormat.POSITION)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        // the portal can be seen from both sides (e.g. mirrors are culled by other means)
        .withCull(false)
        .build();

    private static final List<RenderType> renderTypes = new ArrayList<>();

    /**
     * A texture whose image is a portal view target's color attachment.
     * The target is owned by {@link PortalViewTargetPool}, this doesn't close it.
     */
    private static class PortalViewTexture extends AbstractTexture {
        private final TextureTarget target;

        PortalViewTexture(TextureTarget target) {
            this.target = target;
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        }

        @Override
        public GpuTexture getTexture() {
            return target.getColorTexture();
        }

        @Override
        public GpuTextureView getTextureView() {
            return target.getColorTextureView();
        }

        @Override
        public void close() {
            // the pool owns the target
        }
    }

    /**
     * Called by the pool when it creates its {@code index}-th target.
     */
    static void onTargetCreated(int index, TextureTarget target) {
        Identifier id = Identifier.fromNamespaceAndPath("immersive_portals", "portal_view/" + index);
        Minecraft.getInstance().getTextureManager().register(id, new PortalViewTexture(target));

        while (renderTypes.size() <= index) {
            renderTypes.add(null);
        }
        renderTypes.set(index, RenderType.create(
            "immersive_portals:portal_view_" + index,
            RenderSetup.builder(PORTAL_VIEW_PIPELINE)
                .withTexture("Sampler0", id)
                .createRenderSetup()
        ));
    }

    static void onPoolCleared() {
        for (int i = 0; i < renderTypes.size(); i++) {
            Minecraft.getInstance().getTextureManager().release(
                Identifier.fromNamespaceAndPath("immersive_portals", "portal_view/" + i)
            );
        }
        renderTypes.clear();
    }

    /**
     * Submit the portal's surface. The pose stack must be at the portal's origin (relative to the camera).
     * Does nothing if the portal's view was not rendered this frame.
     */
    public static void submitPortalSurface(
        Portal portal, PoseStack poseStack, SubmitNodeCollector submitNodeCollector
    ) {
        @Nullable RenderType renderType = getRenderTypeForPortal(portal);
        if (renderType == null) {
            return;
        }

        CameraRenderState camera =
            Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        
        if (isCameraInsidePortal(portal, camera.pos)) {
            // The camera is (almost) in the portal plane, e.g. while walking through it.
            // Drawing the mesh would be cut by the near plane and show the world behind the portal,
            // so cover the whole screen with the portal's view instead.
            submitNodeCollector.submitCustomGeometry(
                poseStack, renderType,
                (pose, buffer) -> outputFullScreenQuad(portal, camera, pose.pose(), buffer)
            );
            return;
        }
        
        submitNodeCollector.submitCustomGeometry(
            poseStack, renderType,
            (pose, buffer) -> outputPortalMesh(portal, pose.pose(), buffer)
        );
    }
    
    // how close to the portal plane the camera must be to fill the screen with the portal view
    private static final double INSIDE_PORTAL_DISTANCE = 0.1;
    
    private static boolean isCameraInsidePortal(Portal portal, Vec3 cameraPos) {
        if (Math.abs(portal.getDistanceToPlane(cameraPos)) > INSIDE_PORTAL_DISTANCE) {
            return false;
        }
        return portal.getPortalShape().isBoxInPortalProjection(
            portal.getThisSideState(), new AABB(cameraPos, cameraPos).inflate(0.01)
        );
    }
    
    /**
     * A quad right in front of the camera, covering the whole screen.
     * Vertices are relative to the portal origin (where the pose is).
     */
    private static void outputFullScreenQuad(
        Portal portal, CameraRenderState camera, Matrix4f pose, VertexConsumer buffer
    ) {
        // camera axes in world space, from the view rotation (which includes portal transformations)
        Matrix4f inverseView = new Matrix4f(camera.viewRotationMatrix).invert();
        Vector3f forward = inverseView.transformDirection(new Vector3f(0, 0, -1)).normalize();
        Vector3f right = inverseView.transformDirection(new Vector3f(1, 0, 0)).normalize();
        Vector3f up = inverseView.transformDirection(new Vector3f(0, 1, 0)).normalize();
        
        Vec3 rel = camera.pos.subtract(portal.getOriginPos());
        // 0.07 is just beyond the 0.05 near plane; a half size of 1 covers a FOV of over 170 degrees
        Vector3f center = new Vector3f((float) rel.x, (float) rel.y, (float) rel.z).add(forward.mul(0.07f, new Vector3f()));
        
        Vector3f p00 = new Vector3f(center).sub(right).sub(up);
        Vector3f p10 = new Vector3f(center).add(right).sub(up);
        Vector3f p11 = new Vector3f(center).add(right).add(up);
        Vector3f p01 = new Vector3f(center).sub(right).add(up);
        
        buffer.addVertex(pose, p00.x, p00.y, p00.z);
        buffer.addVertex(pose, p10.x, p10.y, p10.z);
        buffer.addVertex(pose, p11.x, p11.y, p11.z);
        
        buffer.addVertex(pose, p00.x, p00.y, p00.z);
        buffer.addVertex(pose, p11.x, p11.y, p11.z);
        buffer.addVertex(pose, p01.x, p01.y, p01.z);
    }

    private static @Nullable RenderType getRenderTypeForPortal(Portal portal) {
        TextureTarget target = PortalViewRenderer.getViewTarget(portal);
        if (target == null) {
            return null;
        }
        int index = PortalViewRenderer.getTargetIndex(target);
        if (index < 0 || index >= renderTypes.size()) {
            return null;
        }
        return renderTypes.get(index);
    }

    private static void outputPortalMesh(Portal portal, Matrix4f pose, VertexConsumer buffer) {
        // the pose is already at the portal origin
        portal.renderViewAreaMesh(
            Vec3.ZERO,
            (p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z) -> {
                buffer.addVertex(pose, (float) p0x, (float) p0y, (float) p0z);
                buffer.addVertex(pose, (float) p1x, (float) p1y, (float) p1z);
                buffer.addVertex(pose, (float) p2x, (float) p2y, (float) p2z);
            }
        );
    }
}
