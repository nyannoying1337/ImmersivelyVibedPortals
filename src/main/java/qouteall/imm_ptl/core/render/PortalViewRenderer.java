package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
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
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
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
import qouteall.imm_ptl.core.ducks.IECloudRenderer;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.mixin.client.accessor.IELevelRenderer_Clouds;
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
import java.util.IdentityHashMap;
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

    // one CloudRenderer per portal view in a frame, for the same reason
    // (and its mesh buffer is rebuilt when the camera moves to another cloud cell)
    private static final List<CloudRenderer> cloudRendererPool = new ArrayList<>();
    private static int cloudRenderersUsed = 0;

    /**
     * A view: the main view (root), the view through a portal, or a GUI world view.
     * {@link #childTargets} maps each portal visible in this view to the target its content was rendered into.
     */
    public static final class ViewNode {
        public final boolean isMainView;
        public final @Nullable ViewNode parent;
        // null for the main view and GUI world views
        public final @Nullable Portal portal;
        public final Vec3 cameraPos;
        // the product of the portals' camera transformations from the main view to this view
        public final @Nullable Matrix4f cameraTransformation;
        public final Map<Portal, TextureTarget> childTargets = new IdentityHashMap<>(); // global portals have no entity id (hashCode throws)
        public final Map<Portal, ViewNode> children = new IdentityHashMap<>();
        // odd number of mirrors: the view is rendered with a horizontally flipped projection,
        // so that triangle winding (backface culling) stays correct. See docs/rendering-26.3.md.
        public final boolean isMirrored;
        // the crop of the view and the flip of a mirrored view (see PortalViewCrop)
        public PortalViewCrop.Mapping mapping;

        private ViewNode(
            boolean isMainView, @Nullable ViewNode parent, @Nullable Portal portal,
            Vec3 cameraPos, @Nullable Matrix4f cameraTransformation
        ) {
            this.isMainView = isMainView;
            this.parent = parent;
            this.portal = portal;
            this.cameraPos = cameraPos;
            this.cameraTransformation = cameraTransformation;
            this.isMirrored = cameraTransformation != null && cameraTransformation.determinant3x3() < 0;
            this.mapping = PortalViewCrop.Mapping.fullSize(isMirrored);
        }
    }

    private static @Nullable ViewNode rootNode = null;

    // the renderers that portal views used in this frame (see MixinLevelExtractor.modifyShouldInvalidate)
    private static final java.util.Set<LevelRenderer> renderersUsedThisFrame =
        java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    public static boolean isUsedByViewThisFrame(LevelRenderer levelRenderer) {
        return renderersUsedThisFrame.contains(levelRenderer);
    }
    private static @Nullable ViewNode currentNode = null;

    /**
     * True while rendering anything other than the main view (portal views and GUI world views).
     */
    public static boolean isRenderingPortalView() {
        return currentNode != null && !currentNode.isMainView;
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

public static boolean isCurrentViewMirrored() {
        return currentNode != null && currentNode.isMirrored;
    }

    public static int getTargetIndex(TextureTarget target) {
        return targetPool.indexOf(target);
    }
    
    /**
     * When the camera is on the other side of a portal than the player (third person, view bobbing),
     * the whole view shows the portal's destination: it's rendered into this target, which replaces
     * the main image after the main level render (see MixinGameRenderer). Null when not needed this frame.
     */
    private static @Nullable TextureTarget crossPortalViewTarget = null;

    public static @Nullable TextureTarget getCrossPortalViewTarget() {
        return crossPortalViewTarget;
    }

    private static int reusedThisFrame = 0;
    private static long reusedPixelsThisFrame = 0;

    public static void renderPortalViews(DeltaTracker deltaTracker) {
        targetPool.beginFrame();
        reusedThisFrame = 0;
        reusedPixelsThisFrame = 0;
        crossPortalViewTarget = null;
        fogRenderersUsed = 0;
        cloudRenderersUsed = 0;
        rootNode = null;
        currentNode = null;

        if (client.player == null || client.level == null) {
            return;
        }
        if (IPGlobal.renderMode == IPGlobal.RenderMode.none) {
            return;
        }

        ClientWorldLoader.initializeIfNeeded();

        // geometry invalidations that were postponed in the last frame, before any renderer is used in this frame
        for (DimensionRenderHelper helper : new ArrayList<>(ClientWorldLoader.RENDER_HELPER_MAP.values())) {
            helper.processPostponedInvalidation();
        }

        Camera mainCamera = client.gameRenderer.mainCamera();
        if (!mainCamera.isInitialized()) {
            return;
        }
        PortalViewCrop.beginFrame(mainCamera, deltaTracker);

        ViewNode root = new ViewNode(
            true, null, null, TransformationManager.getIsometricAdjustedCameraPos(mainCamera), null
        );
        rootNode = root;

        long startNanos = System.nanoTime();
        try {
            renderChildViews(root, mainCamera, deltaTracker);

            CrossPortalViewRendering.CrossPortalView crossPortalView =
                CrossPortalViewRendering.getCrossPortalView(mainCamera);
            if (crossPortalView != null) {
                TextureTarget target = targetPool.acquire(
                    IPGlobal.portalRenderLimit + 1, client.getWindow().getWidth(), client.getWindow().getHeight()
                );
                if (target != null) {
                    renderWorldIntoTarget(crossPortalView.worldRenderInfo(), target, deltaTracker);
                    crossPortalViewTarget = target;
                }
            }

            // GUI world views submitted during the last frame
            GuiPortalRendering._renderPendingTasks(deltaTracker);
        }
        catch (Throwable e) {
            // don't crash the game because of portal rendering
            LIMITED_LOGGER.invoke(() -> LOGGER.error("[ImmPtl] Error rendering portal views", e));
            recoverFromViewRenderingError();
        }
        finally {
            currentNode = null;
            Stats.frames++;
            Stats.nanos += System.nanoTime() - startNanos;
            Stats.views += targetPool.getUsedCount() - reusedThisFrame;
            Stats.viewPixels += targetPool.getUsedPixels() - reusedPixelsThisFrame;
            Stats.reusedViews += reusedThisFrame;
        }
    }

    /**
     * Cumulative CPU time spent rendering portal views (for benchmarks and the debug screen).
     */
    public static final class Stats {
        public static long frames;
        public static long nanos;
        public static long views;
        // portals in the frustum that were skipped because blocks hide them (PortalOcclusionCulling)
        public static long hiddenPortals;
        // the pixels of the portal view targets (PortalViewCrop)
        public static long viewPixels;
        // views of far portals whose last image was shown again instead (FarPortalViewReuse)
        public static long reusedViews;

        public static void reset() {
            frames = 0;
            nanos = 0;
            views = 0;
            hiddenPortals = 0;
            viewPixels = 0;
            reusedViews = 0;
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
        if (node.isMainView) {
            int count = portals.size();
            portals.removeIf(portal -> PortalOcclusionCulling.isHidden(portal, node.cameraPos));
            Stats.hiddenPortals += count - portals.size();
        }

        @Nullable Matrix4f screenClipMatrix = PortalViewCrop.getScreenClipMatrix();
        Vec3 viewCameraPos = client.gameRenderer.mainCamera().position();
        if (node.isMainView) {
            java.util.Set<Portal> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            seen.addAll(portals);
            FarPortalViewReuse.endFrame(seen);
        }
        for (Portal portal : portals) {
            ClientLevel destLevel = ClientWorldLoader.getOptionalWorld(portal.getDestDim());
            if (destLevel == null) {
                continue;
            }

            PortalViewCrop.Crop crop = PortalViewCrop.computeCrop(portal, viewCameraPos, screenClipMatrix, node.mapping);
            if (crop == null) {
                // not in the (cropped) part of the screen that this view shows
                continue;
            }
            // far portals of the main view: show the last image again if nothing moved (FarPortalViewReuse)
            int updateInterval = node.isMainView && screenClipMatrix != null ?
                FarPortalViewReuse.getUpdateInterval(portal, viewCameraPos, crop) : 1;
            if (updateInterval > 1) {
                PortalViewCrop.Mapping lastMapping = FarPortalViewReuse.getReusableMapping(
                    portal, viewCameraPos, screenClipMatrix, crop, updateInterval
                );
                TextureTarget lastImage = lastMapping == null ? null :
                    targetPool.reuse(portal, IPGlobal.portalRenderLimit);
                if (lastImage != null) {
                    PortalSurfaceRendering.setSamplingMatrix(
                        targetPool.indexOf(lastImage), PortalViewCrop.samplingMatrix(node.mapping, lastMapping)
                    );
                    node.childTargets.put(portal, lastImage);
                    reusedThisFrame++;
                    reusedPixelsThisFrame += (long) lastImage.width * lastImage.height;
                    continue;
                }
            }
            else {
                FarPortalViewReuse.forget(portal);
            }

            TextureTarget target = targetPool.acquire(
                IPGlobal.portalRenderLimit, crop.width, crop.height, updateInterval > 1 ? portal : null
            );
            if (target == null) {
                break;
            }

            Matrix4f cameraTransformation = PortalRenderer.combineNullable(
                node.cameraTransformation == null ? null : new Matrix4f(node.cameraTransformation),
                portal.getAdditionalCameraTransformation()
            );
            ViewNode child = new ViewNode(
                false, node, portal,
                portal.transformPoint(node.cameraPos),
                cameraTransformation
            );
            child.mapping = crop.finish(target.width, target.height, child.isMirrored);
            PortalSurfaceRendering.setSamplingMatrix(
                targetPool.indexOf(target), PortalViewCrop.samplingMatrix(node.mapping, child.mapping)
            );
            node.childTargets.put(portal, target);
            node.children.put(portal, child);

            WorldRenderInfo worldRenderInfo = new WorldRenderInfo.Builder()
                .setWorld(destLevel)
                .setCameraPos(child.cameraPos)
                .setCameraTransformation(portal.getAdditionalCameraTransformation())
                .setOverwriteCameraTransformation(false)
                .setDescription(portal.getDiscriminator())
                .setRenderDistance(PortalRenderer.getPortalRenderDistance(portal))
                .setDoRenderHand(false)
                .setEnableViewBobbing(true)
                .setDoRenderSky(!portal.isFuseView())
                .build();

            renderViewAndChildren(child, destLevel, target, worldRenderInfo, false, mainCamera, deltaTracker);
            if (updateInterval > 1) {
                FarPortalViewReuse.onRendered(
                    portal, viewCameraPos, screenClipMatrix, crop, child.mapping, updateInterval
                );
            }
        }
    }

    /**
     * Render a world view that's not seen through a portal (e.g. for a GUI) into the target.
     * Portals inside it are rendered too. Must be called while portal views are rendered
     * (see {@link GuiPortalRendering#_renderPendingTasks}).
     * The target must be window-sized and have a depth buffer (the view is not cropped).
     */
    public static void renderWorldIntoTarget(
        WorldRenderInfo worldRenderInfo, RenderTarget target, DeltaTracker deltaTracker
    ) {
        Matrix4f transformation = worldRenderInfo.cameraTransformation == null ?
            null : new Matrix4f(worldRenderInfo.cameraTransformation);
        ViewNode node = new ViewNode(
            false, null, null, worldRenderInfo.cameraPos, transformation
        );
        renderViewAndChildren(
            node, worldRenderInfo.world, target, worldRenderInfo,
            worldRenderInfo.overwriteCameraTransformation,
            client.gameRenderer.mainCamera(), deltaTracker
        );
    }

    /**
     * A view that threw in the middle of LevelRenderer.render leaves shared render state behind:
     * the feature render dispatcher's single prepared frame stays "in use", so the main view's render would throw
     * "PreparedFrame already in use" and crash the game.
     */
    private static void recoverFromViewRenderingError() {
        try {
            ((qouteall.imm_ptl.core.mixin.client.render.IEFeatureRenderDispatcher)
                client.gameRenderer.featureRenderDispatcher()).ip_getPreparedFrame().close();
        }
        catch (IllegalStateException | NullPointerException ignored) {
            // it was not in use ("Frame not in use")
        }
        RenderSystem.isRenderingLevel = false;
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

    /**
     * The renderer for a view of that dimension at that camera position, with its grid positioned for the view
     * ({@link DimensionRenderHelper#selectForView}). Not with Sodium (its renderer is not bound to a fixed grid).
     */
    private static DimensionRenderHelper selectRenderHelper(ClientLevel destLevel, Vec3 cameraPos) {
        DimensionRenderHelper helper = ClientWorldLoader.getDimensionRenderHelper(destLevel.dimension());
        if (SodiumInterface.invoker.isSodiumPresent()) {
            return helper;
        }
        return helper.selectForView(cameraPos, RenderStates.originalPlayerDimension == destLevel.dimension());
    }

    /**
     * @param replaceRotation if true, the view rotation is {@code node.cameraTransformation} alone,
     *                        otherwise the main camera's rotation times it
     */
    private static void renderViewAndChildren(
        ViewNode node, ClientLevel destLevel, RenderTarget target,
        WorldRenderInfo worldRenderInfo, boolean replaceRotation,
        Camera mainCamera, DeltaTracker deltaTracker
    ) {
        @Nullable Portal portal = node.portal;

        GameRenderer gameRenderer = client.gameRenderer;
        IEGameRenderer ieGameRenderer = (IEGameRenderer) gameRenderer;
        DimensionRenderHelper renderHelper = selectRenderHelper(destLevel, node.cameraPos);

        // save the state that is switched
        ViewNode oldNode = currentNode;
        ClientLevel oldLevel = client.level;
        LevelRenderer oldLevelRenderer = client.levelRenderer;
        LevelExtractor oldLevelExtractor = client.levelExtractor;
        Lightmap oldLightmap = ieGameRenderer.ip_getLightmap();
        FogRenderer oldFogRenderer = ieGameRenderer.ip_getFogRenderer();
        CloudRenderer oldCloudRenderer = renderHelper.levelRenderer.cloudRenderer();
        Camera oldCamera = gameRenderer.mainCamera();
        @Nullable RenderTarget oldTargetOverride = ieGameRenderer.ip_getMainRenderTargetOverride();
        boolean oldSmartCull = client.smartCull;
        HitResult oldHitResult = client.hitResult;

        if (portal != null) {
            PortalRendering.pushPortalLayer(portal);
        }
        WorldRenderInfo.pushRenderInfo(worldRenderInfo);

        // set up after the portal layer is pushed:
        // preparing the cull frustum runs FrustumCuller, which depends on the portal being rendered
        Camera viewCamera = new Camera();
        ((IECamera) viewCamera).ip_setupAsPortalView(
            mainCamera, destLevel, node.cameraPos, node.cameraTransformation, replaceRotation
        );

        currentNode = node;
        client.level = destLevel;
        ((IEMinecraftClient) client).ip_setLevelRendererAndExtractor(
            renderHelper.levelRenderer, renderHelper.levelExtractor
        );
        ((IEParticleManager) client.particleEngine).ip_setWorld(destLevel);
        ieGameRenderer.ip_setLightmap(renderHelper.lightmap);
        ieGameRenderer.ip_setFogRenderer(acquireFogRenderer());
        ((IELevelRenderer_Clouds) renderHelper.levelRenderer).ip_setCloudRenderer(acquireCloudRenderer());
        ieGameRenderer.ip_setCamera(viewCamera);
        // directional block lighting differs per dimension (e.g. the nether)
        gameRenderer.lighting().updateLevel(destLevel.dimensionType().cardinalLightType());
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

            if (portal != null) {
                PortalRendering.onBeginPortalWorldRendering();
            }
            Profiler.get().push("render_portal_view");
            renderCurrentView(target, renderHelper, deltaTracker);
            Profiler.get().pop();
            // its buffers are now used by this frame (an invalidation of it must wait for the next frame)
            renderersUsedThisFrame.add(renderHelper.levelRenderer);
            if (portal != null) {
                PortalRendering.onEndPortalWorldRendering();
            }
        }
        finally {
            // restore
            ieGameRenderer.ip_setMainRenderTargetOverride(oldTargetOverride);
            ieGameRenderer.ip_setCamera(oldCamera);
            ieGameRenderer.ip_setFogRenderer(oldFogRenderer);
            ((IELevelRenderer_Clouds) renderHelper.levelRenderer).ip_setCloudRenderer(oldCloudRenderer);
            ieGameRenderer.ip_setLightmap(oldLightmap);
            ((IEParticleManager) client.particleEngine).ip_setWorld(oldLevel);
            ((IEMinecraftClient) client).ip_setLevelRendererAndExtractor(oldLevelRenderer, oldLevelExtractor);
            client.level = oldLevel;
            if (oldLevel != null) {
                gameRenderer.lighting().updateLevel(oldLevel.dimensionType().cardinalLightType());
            }
            client.smartCull = oldSmartCull;
            client.hitResult = oldHitResult;
            currentNode = oldNode;

            WorldRenderInfo.popRenderInfo();
            if (portal != null) {
                PortalRendering.popPortalLayer();
            }
        }
    }

    /**
     * Render the current view (whose state is switched in) into the target.
     * Mirrors what GameRenderer.extract() and GameRenderer.render() do for the main view.
     */
    private static void renderCurrentView(
        RenderTarget target, DimensionRenderHelper renderHelper, DeltaTracker deltaTracker
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
        // The view's crop (PortalViewCrop), and for a mirror view a horizontal flip: its rotation contains a
        // reflection, which reverses triangle winding, so backface culling would cull the wrong faces; the flip
        // reverses it back. The portal surface samples the view accordingly (PortalViewCrop.samplingMatrix).
        // Done on the extracted state, which is the only source of the level projection
        // (GameRenderer.renderLevel), so other renderers that capture it (e.g. Sodium) get it too.
        if (currentNode != null && !currentNode.mapping.isIdentity()) {
            gameRenderState.levelRenderState.cameraRenderState.projectionMatrix.mulLocal(
                currentNode.mapping.toClipMatrix()
            );
        }
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

        try {
            // projection, fog, LevelRenderer.render (the hand is skipped in portal views)
            gameRenderer.renderLevel();
        }
        finally {
            PortalClipping.resetAfterView();
        }
        // after rendering: Sodium builds the view's render lists while rendering
        if (ViewDiagnostics.enabled && client.level != null) {
            ViewDiagnostics.recordView(
                describeCurrentView(), client.level, client.levelRenderer, gameRenderer.mainCamera().position()
            );
        }
    }

    private static String describeCurrentView() {
        if (currentNode == null) {
            return "view";
        }
        if (currentNode.portal == null) {
            return "cross-portal or GUI view";
        }
        int depth = 0;
        for (ViewNode n = currentNode; n.parent != null; n = n.parent) {
            depth++;
        }
        return "portal " + currentNode.portal.getId() + " (layer " + depth + ")";
    }

    private static FogRenderer acquireFogRenderer() {
        if (fogRenderersUsed >= fogRendererPool.size()) {
            fogRendererPool.add(new FogRenderer());
        }
        return fogRendererPool.get(fogRenderersUsed++);
    }

    private static CloudRenderer acquireCloudRenderer() {
        if (cloudRenderersUsed >= cloudRendererPool.size()) {
            CloudRenderer cloudRenderer = new CloudRenderer();
            ((IECloudRenderer) cloudRenderer).ip_reloadNow(client.getResourceManager());
            cloudRendererPool.add(cloudRenderer);
        }
        return cloudRendererPool.get(cloudRenderersUsed++);
    }

    /**
     * Called at the end of each frame.
     */
    public static void onEndFrame() {
        renderersUsedThisFrame.clear();
        ViewDiagnostics.onEndFrame();
        for (FogRenderer fogRenderer : fogRendererPool) {
            fogRenderer.endFrame();
        }
        for (CloudRenderer cloudRenderer : cloudRendererPool) {
            cloudRenderer.endFrame();
        }
    }

    public static void onResourceReload() {
        for (CloudRenderer cloudRenderer : cloudRendererPool) {
            ((IECloudRenderer) cloudRenderer).ip_reloadNow(client.getResourceManager());
        }
    }

    public static void init() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(() -> {
            targetPool.cleanUp();
            FarPortalViewReuse.cleanUp();
            for (FogRenderer fogRenderer : fogRendererPool) {
                fogRenderer.close();
            }
            fogRendererPool.clear();
            for (CloudRenderer cloudRenderer : cloudRendererPool) {
                cloudRenderer.close();
            }
            cloudRendererPool.clear();
            rootNode = null;
            currentNode = null;
        });
    }
}
