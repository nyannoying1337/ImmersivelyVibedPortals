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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
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
    public static final RenderPipeline PORTAL_VIEW_PIPELINE = createPipeline("pipeline/portal_view", false);
    // samples the view with x flipped: a mirrored view seen from a non-mirrored view or vice versa
    // (mirrored views are rendered with a horizontally flipped projection, see MixinGameRenderer)
    public static final RenderPipeline PORTAL_VIEW_FLIPPED_PIPELINE =
        createPipeline("pipeline/portal_view_flipped", true);

    private static RenderPipeline createPipeline(String path, boolean flipX) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("immersive_portals", path))
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
        .withCull(false);
        if (flipX) {
            builder.withShaderDefine("IMMPTL_FLIP_X");
        }
        return builder.build();
    }

    private static final List<RenderType> renderTypes = new ArrayList<>();
    private static final List<RenderType> flippedRenderTypes = new ArrayList<>();

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
            flippedRenderTypes.add(null);
        }
        renderTypes.set(index, RenderType.create(
            "immersive_portals:portal_view_" + index,
            RenderSetup.builder(PORTAL_VIEW_PIPELINE)
                .withTexture("Sampler0", id)
                .createRenderSetup()
        ));
        flippedRenderTypes.set(index, RenderType.create(
            "immersive_portals:portal_view_flipped_" + index,
            RenderSetup.builder(PORTAL_VIEW_FLIPPED_PIPELINE)
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
        flippedRenderTypes.clear();
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

        submitNodeCollector.submitCustomGeometry(
            poseStack, renderType,
            (pose, buffer) -> outputPortalMesh(portal, pose.pose(), buffer)
        );
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
        return PortalViewRenderer.shouldFlipSampling(portal) ?
            flippedRenderTypes.get(index) : renderTypes.get(index);
    }

    /**
     * The portal's own mesh. When the camera is very close to or inside the portal,
     * parts of it are behind the camera or nearer than the near plane; the shader emulates
     * depth clamp, so it covers exactly the rays that go through the portal (like the original mod).
     */
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
