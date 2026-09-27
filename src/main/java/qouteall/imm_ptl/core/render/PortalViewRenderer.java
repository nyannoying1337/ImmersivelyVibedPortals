package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.render.context_management.DimensionRenderHelper;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;
import qouteall.q_misc_util.my_util.LimitedLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders what is seen through portals, before the main view is extracted and rendered.
 * See docs/rendering-26.3.md.
 * <p>
 * For each portal visible from a view, the destination is rendered (children first, recursively)
 * into its own offscreen target. When the parent view is rendered, the portal surface samples that target.
 * All of this is sequential on the render thread, so the per-dimension LevelRenderers can share
 * {@link GameRenderer#gameRenderState()}: each view overwrites it and the main view extracts last.
 */
public class PortalViewRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LimitedLogger LIMITED_LOGGER = new LimitedLogger(20);
    private static final Minecraft client = Minecraft.getInstance();

    private static final PortalViewTargetPool targetPool = new PortalViewTargetPool();

    // one FogRenderer per portal view in a frame (its buffer holds only one value per frame)
    private static final List<FogRenderer> fogRendererPool = new ArrayList<>();
    private static int fogRenderersUsed = 0;

    /**
     * A view: the main view (root) or the view through a portal.
     * {@link #childTargets} maps each portal visible in this view to the target its content was rendered into.
     */
    public static final class ViewNode {
        public final @Nullable ViewNode parent;
        public final @Nullable Portal portal;
        public final Vec3 cameraPos;
        // the product of the portals' camera transformations from the main view to this view
        public final @Nullable Matrix4f cameraTransformation;
        public final Map<Portal, TextureTarget> childTargets = new HashMap<>();

        private ViewNode(
            @Nullable ViewNode parent, @Nullable Portal portal,
            Vec3 cameraPos, @Nullable Matrix4f cameraTransformation
        ) {
            this.parent = parent;
            this.portal = portal;
            this.cameraPos = cameraPos;
            this.cameraTransformation = cameraTransformation;
        }
    }

    private static @Nullable ViewNode rootNode = null;
    private static @Nullable ViewNode currentNode = null;

    public static boolean isRenderingPortalView() {
        return currentNode != null && currentNode.parent != null;
    }

    /**
     * The target that the content behind this portal was rendered into, for the view being rendered now.
     * Null if the portal's content was not rendered this frame (then the portal surface is not drawn).
     */
    public static @Nullable TextureTarget getViewTarget(Portal portal) {
        ViewNode node = currentNode != null ? currentNode : rootNode;
        if (node == null) {
            return null;
        }
        return node.childTargets.get(portal);
    }

    public static int getTargetIndex(TextureTarget target) {
        return targetPool.indexOf(target);
    }
    
    public static void renderPortalViews(DeltaTracker deltaTracker) {
        targetPool.beginFrame();
        fogRenderersUsed = 0;
        rootNode = null;
        currentNode = null;

        if (client.player == null || client.level == null) {
            return;
        }
        if (IPGlobal.renderMode == IPGlobal.RenderMode.none) {
            return;
        }

        ClientWorldLoader.initializeIfNeeded();

        Camera mainCamera = client.gameRenderer.mainCamera();
        if (!mainCamera.isInitialized()) {
            return;
        }

        ViewNode root = new ViewNode(
            null, null, TransformationManager.getIsometricAdjustedCameraPos(mainCamera), null
        );
        rootNode = root;

        try {
            renderChildViews(root, mainCamera, deltaTracker);
        }
        catch (Throwable e) {
            // don't crash the game because of portal rendering
            if (LIMITED_LOGGER.tryDecrement()) {
                LOGGER.error("[ImmPtl] Error rendering portal views", e);
            }
        }
        finally {
            currentNode = null;
        }
    }

    /**
     * Must be called with the view context of {@code node} active
     * (for the root: the normal client state).
     */
    private static void renderChildViews(ViewNode node, Camera mainCamera, DeltaTracker deltaTracker) {
        if (PortalRendering.getPortalLayer() >= PortalRendering.getMaxPortalLayer()) {
            return;
        }

        List<Portal> portals = collectVisiblePortals(client.gameRenderer.mainCamera().getCullFrustum());

        for (Portal portal : portals) {
            TextureTarget target = targetPool.acquire(IPGlobal.portalRenderLimit);
            if (target == null) {
                break;
            }

            ClientLevel destLevel = ClientWorldLoader.getOptionalWorld(portal.getDestDim());
            if (destLevel == null) {
                continue;
            }

            Matrix4f cameraTransformation = PortalRenderer.combineNullable(
                node.cameraTransformation == null ? null : new Matrix4f(node.cameraTransformation),
                portal.getAdditionalCameraTransformation()
            );
            ViewNode child = new ViewNode(
                node, portal,
                portal.transformPoint(node.cameraPos),
                cameraTransformation
            );
            node.childTargets.put(portal, target);

            renderViewAndChildren(child, destLevel, target, mainCamera, deltaTracker);
        }
    }

    private static List<Portal> collectVisiblePortals(Frustum frustum) {
        ClientLevel level = client.level;
        assert level != null;

        List<Portal> result = new ArrayList<>();
        for (Portal globalPortal : GlobalPortalStorage.getGlobalPortals(level)) {
            if (!PortalRenderer.shouldSkipRenderingPortal(globalPortal, () -> frustum)) {
                result.add(globalPortal);
            }
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Portal portal) {
                if (!PortalRenderer.shouldSkipRenderingPortal(portal, () -> frustum)) {
                    result.add(portal);
                }
            }
        }

        Vec3 cameraPos = client.gameRenderer.mainCamera().position();
        result.sort(Comparator.comparingDouble(
            p -> p.getDistanceToNearestPointInPortal(cameraPos)
        ));
        return result;
    }

    private static void renderViewAndChildren(
        ViewNode node, ClientLevel destLevel, TextureTarget target,
        Camera mainCamera, DeltaTracker deltaTracker
    ) {
        Portal portal = node.portal;
        assert portal != null;

        GameRenderer gameRenderer = client.gameRenderer;
        IEGameRenderer ieGameRenderer = (IEGameRenderer) gameRenderer;
        DimensionRenderHelper renderHelper = ClientWorldLoader.getDimensionRenderHelper(destLevel.dimension());

        Camera viewCamera = new Camera();
        ((IECamera) viewCamera).ip_setupAsPortalView(
            mainCamera, destLevel, node.cameraPos, node.cameraTransformation
        );

        // save the state that is switched
        ViewNode oldNode = currentNode;
        ClientLevel oldLevel = client.level;
        LevelRenderer oldLevelRenderer = client.levelRenderer;
        LevelExtractor oldLevelExtractor = client.levelExtractor;
        Lightmap oldLightmap = ieGameRenderer.ip_getLightmap();
        FogRenderer oldFogRenderer = ieGameRenderer.ip_getFogRenderer();
        Camera oldCamera = gameRenderer.mainCamera();
        @Nullable RenderTarget oldTargetOverride = ieGameRenderer.ip_getMainRenderTargetOverride();
        boolean oldSmartCull = client.smartCull;
        HitResult oldHitResult = client.hitResult;

        PortalRendering.pushPortalLayer(portal);
        WorldRenderInfo.pushRenderInfo(
            new WorldRenderInfo.Builder()
                .setWorld(destLevel)
                .setCameraPos(node.cameraPos)
                .setCameraTransformation(portal.getAdditionalCameraTransformation())
                .setOverwriteCameraTransformation(false)
                .setDescription(portal.getDiscriminator())
                .setRenderDistance(PortalRenderer.getPortalRenderDistance(portal))
                .setDoRenderHand(false)
                .setEnableViewBobbing(true)
                .setDoRenderSky(!portal.isFuseView())
                .build()
        );

        currentNode = node;
        client.level = destLevel;
        ((IEMinecraftClient) client).ip_setLevelRendererAndExtractor(
            renderHelper.levelRenderer, renderHelper.levelExtractor
        );
        ((IEParticleManager) client.particleEngine).ip_setWorld(destLevel);
        ieGameRenderer.ip_setLightmap(renderHelper.lightmap);
        ieGameRenderer.ip_setFogRenderer(acquireFogRenderer());
        ieGameRenderer.ip_setCamera(viewCamera);
        // the cave culling BFS starts from the camera, which is usually right behind the destination portal
        client.smartCull = false;
        if (BlockManipulationClient.remotePointedDim == destLevel.dimension()) {
            client.hitResult = BlockManipulationClient.remoteHitResult;
        }
        if (!PortalRendering.shouldRenderHitResult()) {
            client.hitResult = null;
        }

        try {
            // portals seen inside this view are rendered first
            renderChildViews(node, mainCamera, deltaTracker);

            // children may have switched the target; set it for this view
            ieGameRenderer.ip_setMainRenderTargetOverride(target);

            PortalRendering.onBeginPortalWorldRendering();
            Profiler.get().push("render_portal_view");
            renderCurrentView(target, renderHelper, deltaTracker);
            Profiler.get().pop();
            PortalRendering.onEndPortalWorldRendering();
        }
        finally {
            // restore
            ieGameRenderer.ip_setMainRenderTargetOverride(oldTargetOverride);
            ieGameRenderer.ip_setCamera(oldCamera);
            ieGameRenderer.ip_setFogRenderer(oldFogRenderer);
            ieGameRenderer.ip_setLightmap(oldLightmap);
            ((IEParticleManager) client.particleEngine).ip_setWorld(oldLevel);
            ((IEMinecraftClient) client).ip_setLevelRendererAndExtractor(oldLevelRenderer, oldLevelExtractor);
            client.level = oldLevel;
            client.smartCull = oldSmartCull;
            client.hitResult = oldHitResult;
            currentNode = oldNode;

            WorldRenderInfo.popRenderInfo();
            PortalRendering.popPortalLayer();
        }
    }

    /**
     * Render the current view (whose state is switched in) into the target.
     * Mirrors what GameRenderer.extract() and GameRenderer.render() do for the main view.
     */
    private static void renderCurrentView(
        TextureTarget target, DimensionRenderHelper renderHelper, DeltaTracker deltaTracker
    ) {
        GameRenderer gameRenderer = client.gameRenderer;
        IEGameRenderer ieGameRenderer = (IEGameRenderer) gameRenderer;
        GameRenderState gameRenderState = gameRenderer.gameRenderState();
        float worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);

        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            target.getColorTexture(), new Vector4f(0, 0, 0, 0), target.getDepthTexture(), 0.0
        );

        // the lightmap of a dimension is the same in all views of the frame
        if (renderHelper.lightmapRenderedFrame != RenderStates.frameIndex) {
            ieGameRenderer.ip_getLightmapRenderStateExtractor().extract(gameRenderState.lightmapRenderState, 1.0F);
            renderHelper.lightmap.render(gameRenderState.lightmapRenderState);
            renderHelper.lightmapRenderedFrame = RenderStates.frameIndex;
        }

        // extract
        ieGameRenderer.ip_extractCamera(deltaTracker, worldPartialTicks);
        client.levelExtractor.extract(deltaTracker, gameRenderer.mainCamera(), worldPartialTicks);

        // camera position (and portal clip plane) uniform, written in command order before this view's draws
        PortalClipping.setupForCurrentView();
        ieGameRenderer.ip_getGlobalSettingsUniform().update(
            target.width,
            target.height,
            gameRenderState.optionsRenderState.glintStrength,
            gameRenderState.levelRenderState.gameTime,
            gameRenderState.levelRenderState.worldPartialTicks,
            gameRenderState.optionsRenderState.menuBackgroundBlurriness,
            gameRenderState.levelRenderState.cameraRenderState.pos,
            gameRenderState.optionsRenderState.textureFiltering == TextureFilteringMethod.RGSS
        );

        // projection, fog, LevelRenderer.render (the hand is skipped in portal views)
        gameRenderer.renderLevel();

        PortalClipping.resetAfterView();
    }

    private static FogRenderer acquireFogRenderer() {
        if (fogRenderersUsed >= fogRendererPool.size()) {
            fogRendererPool.add(new FogRenderer());
        }
        return fogRendererPool.get(fogRenderersUsed++);
    }

    /**
     * Called at the end of each frame.
     */
    public static void onEndFrame() {
        for (FogRenderer fogRenderer : fogRendererPool) {
            fogRenderer.endFrame();
        }
    }

    public static void init() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(() -> {
            targetPool.cleanUp();
            for (FogRenderer fogRenderer : fogRendererPool) {
                fogRenderer.close();
            }
            fogRendererPool.clear();
            rootNode = null;
            currentNode = null;
        });
    }
}
