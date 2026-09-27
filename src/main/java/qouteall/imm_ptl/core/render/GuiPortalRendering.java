package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.my_util.LimitedLogger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a world view into a render target that can then be drawn in a GUI
 * (see {@link qouteall.imm_ptl.core.api.example.ExampleGuiPortalRendering}).
 * <p>
 * In 26.3 a world can't be rendered after the frame is rendered or inside another world render
 * (see docs/rendering-26.3.md), so the submitted tasks are rendered in the next frame,
 * together with the portal views, before the main view is extracted.
 * The target must have a depth buffer.
 * Its content is premultiplied-alpha-like: when the sky is not rendered, the background has 0 alpha.
 */
@Environment(EnvType.CLIENT)
public class GuiPortalRendering {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LimitedLogger LIMITED_LOGGER = new LimitedLogger(1);

    @Nullable
    private static RenderTarget renderingFrameBuffer = null;

    private static final Map<RenderTarget, WorldRenderInfo> renderingTasks = new LinkedHashMap<>();

    @Nullable
    public static RenderTarget getRenderingFrameBuffer() {
        return renderingFrameBuffer;
    }

    public static boolean isRendering() {
        return getRenderingFrameBuffer() != null;
    }

    /**
     * Render the world view into the render target in the next frame.
     * The target is resized to the window size.
     */
    public static void submitNextFrameRendering(
        WorldRenderInfo worldRenderInfo,
        RenderTarget renderTarget
    ) {
        if (!ClientWorldLoader.getIsInitialized()) {
            LOGGER.error("Trying to submit world rendering task before client world is initialized", new Throwable());
            return;
        }

        Validate.isTrue(renderTarget.hasDepth(), "The GUI portal render target needs a depth buffer");

        int width = Minecraft.getInstance().getWindow().getWidth();
        int height = Minecraft.getInstance().getWindow().getHeight();
        if (renderTarget.width != width || renderTarget.height != height) {
            renderTarget.resize(width, height);
            LOGGER.info("Resized Framebuffer for GUI Portal Rendering");
        }

        // a newer submission for the same target replaces the older one
        renderingTasks.put(renderTarget, worldRenderInfo);
    }

    /**
     * Not API.
     * Must be called in the same place as portal view rendering
     * ({@link PortalViewRenderer#renderPortalViews}, at the head of GameRenderer.extract),
     * after the main view's portal views are rendered.
     */
    public static void _renderPendingTasks(DeltaTracker deltaTracker) {
        if (renderingTasks.isEmpty()) {
            return;
        }

        try {
            renderingTasks.forEach((renderTarget, worldRenderInfo) -> {
                Validate.isTrue(renderingFrameBuffer == null);
                renderingFrameBuffer = renderTarget;
                try {
                    renderWorldIntoTarget(worldRenderInfo, renderTarget, deltaTracker);
                }
                finally {
                    renderingFrameBuffer = null;
                }
            });
        }
        finally {
            renderingTasks.clear();
        }
    }

    private static void renderWorldIntoTarget(
        WorldRenderInfo worldRenderInfo, RenderTarget renderTarget, DeltaTracker deltaTracker
    ) {
        GpuTexture colorTexture = renderTarget.getColorTexture();
        GpuTexture depthTexture = renderTarget.getDepthTexture();
        Validate.notNull(colorTexture);
        Validate.notNull(depthTexture);

        // clear with 0 alpha, so that the parts without the sky are transparent
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            colorTexture, new Vector4f(0, 0, 0, 0), depthTexture, 0.0 // reversed-Z
        );

        // TODO(26.3): render the world. Needs a hook in PortalViewRenderer (not owned here):
        //  public static void renderWorldIntoTarget(WorldRenderInfo worldRenderInfo, RenderTarget target, DeltaTracker deltaTracker)
        //  that does what renderViewAndChildren does, but for a view without a portal
        //  (level = worldRenderInfo.world, camera at worldRenderInfo.cameraPos with
        //  worldRenderInfo.cameraTransformation, no PortalRendering layer push, no clip plane,
        //  WorldRenderInfo.pushRenderInfo(worldRenderInfo), no hand, portals inside rendered as children).
        //  Then replace this TODO with a call to it, and call _renderPendingTasks from renderPortalViews.
        LIMITED_LOGGER.invoke(() -> LOGGER.warn("[ImmPtl] GUI portal rendering is not implemented yet in 26.3"));
    }

    // not API
    public static void _init() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(renderingTasks::clear);
    }
}
