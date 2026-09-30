package qouteall.imm_ptl.core.render.context_management;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ducks.IEClientWorld;
import qouteall.imm_ptl.core.ducks.IECloudRenderer;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.q_misc_util.Helper;

/**
 * The rendering objects of one client dimension.
 * In 26.3 a dimension is rendered by a {@link LevelExtractor} (game state -> render state)
 * and a {@link LevelRenderer} (render state -> GPU). They are always used together.
 * <p>
 * The dimension that was loaded first uses vanilla's own instances
 * ({@link Minecraft#levelRenderer}, {@link Minecraft#levelExtractor} and the GameRenderer lightmap).
 * Other dimensions get their own instances.
 * When the player changes dimension, the instances in Minecraft/GameRenderer are switched to that dimension's,
 * so "the current instances" (which vanilla resizes/ends/closes by itself) can belong to any dimension.
 */
public class DimensionRenderHelper {
    private static final Minecraft client = Minecraft.getInstance();

    public final ClientLevel world;
    public final LevelRenderer levelRenderer;
    public final LevelExtractor levelExtractor;
    public final Lightmap lightmap;
    
    // true for the instances that vanilla created (Minecraft.levelRenderer etc. at initialization)
    public final boolean isVanillaOriginal;

    // false for a far helper, which uses the lightmap of its dimension's helper
    private final boolean ownsLightmap;

    // see getOrCreateFarHelper
    private @Nullable DimensionRenderHelper farHelper;
    private long lastUsedFrame;
    private static final int FAR_HELPER_IDLE_FRAMES = 600;


    // the frame index at which the lightmap was last rendered, see RenderStates.frameIndex
    public long lightmapRenderedFrame = -1;

    private DimensionRenderHelper(
        ClientLevel world, LevelRenderer levelRenderer, LevelExtractor levelExtractor,
        Lightmap lightmap, boolean isVanillaOriginal
    ) {
        this(world, levelRenderer, levelExtractor, lightmap, isVanillaOriginal, true);
    }

    private DimensionRenderHelper(
        ClientLevel world, LevelRenderer levelRenderer, LevelExtractor levelExtractor,
        Lightmap lightmap, boolean isVanillaOriginal, boolean ownsLightmap
    ) {
        this.world = world;
        this.levelRenderer = levelRenderer;
        this.levelExtractor = levelExtractor;
        this.lightmap = lightmap;
        this.isVanillaOriginal = isVanillaOriginal;
        this.ownsLightmap = ownsLightmap;
    }

    /**
     * A second renderer/extractor of this dimension, for portal views far away from where this helper's
     * ViewArea (a fixed grid of the render distance) is. The renderer of the player's dimension is centered on the
     * player, so without it a portal to a far-away place of the same dimension shows nothing.
     * It renders the same ClientLevel (which forwards block changes to it, see IEClientWorld.ip_setExtraExtractor)
     * and is released when it's not used for a while.
     */
    public DimensionRenderHelper getOrCreateFarHelper() {
        if (farHelper == null) {
            Pending pending = createNew();
            setExtractorLevel(pending.levelRenderer(), pending.levelExtractor(), world);
            ResourceManager resourceManager = client.getResourceManager();
            pending.levelExtractor().onResourceManagerReload(resourceManager);
            ((IECloudRenderer) pending.levelRenderer().cloudRenderer()).ip_reloadNow(resourceManager);
            farHelper = new DimensionRenderHelper(
                world, pending.levelRenderer(), pending.levelExtractor(), lightmap, false, false
            );
            ((IEClientWorld) world).ip_setExtraExtractor(farHelper.levelExtractor);
            Helper.log("Created far view renderer for " + world.dimension().identifier());
        }
        farHelper.lastUsedFrame = RenderStates.frameIndex;
        return farHelper;
    }

    /**
     * If the renderer is a far helper's (see getOrCreateFarHelper), the renderer of the helper it belongs to.
     * Both extractors take the section and chunk load changes from the same ClientLevel, and each change is given out
     * once, so the far helper's extraction passes them on to that renderer (see MixinLevelRenderer).
     */
    public static @Nullable LevelRenderer getFarHelperOwnerRenderer(LevelRenderer levelRenderer) {
        for (DimensionRenderHelper helper : qouteall.imm_ptl.core.ClientWorldLoader.RENDER_HELPER_MAP.values()) {
            if (helper.farHelper != null && helper.farHelper.levelRenderer == levelRenderer) {
                return helper.levelRenderer;
            }
        }
        return null;
    }

    private void releaseFarHelper() {
        if (farHelper != null) {
            ((IEClientWorld) world).ip_setExtraExtractor(null);
            farHelper.cleanUp();
            farHelper = null;
            Helper.log("Released far view renderer for " + world.dimension().identifier());
        }
    }

    /**
     * For the dimension the client is in when ImmPtl initializes. Uses vanilla's instances.
     */
    public static DimensionRenderHelper ofVanillaInstances(ClientLevel world) {
        return new DimensionRenderHelper(
            world, client.levelRenderer, client.levelExtractor,
            ((IEGameRenderer) client.gameRenderer).ip_getLightmap(),
            true
        );
    }

    /**
     * Create the renderer and extractor for a new client dimension.
     * The ClientLevel needs the extractor in its constructor,
     * so call this first, then create the level, then {@link #bindLevel}.
     */
    public static Pending createNew() {
        LevelRenderer levelRenderer = new LevelRenderer(
            client.getEntityRenderDispatcher(),
            client.getBlockEntityRenderDispatcher(),
            client.getModelManager(),
            client.getTextureManager(),
            client.getAtlasManager(),
            client.getShaderManager(),
            client.gameRenderer,
            client.getWindow().getWidth(),
            client.getWindow().getHeight()
        );
        LevelExtractor levelExtractor = new LevelExtractor(
            client, client.gameRenderer.gameRenderState().levelRenderState, levelRenderer
        );
        return new Pending(levelRenderer, levelExtractor);
    }

    public record Pending(LevelRenderer levelRenderer, LevelExtractor levelExtractor) {
        public DimensionRenderHelper bindLevel(ClientLevel world) {
            setExtractorLevel(levelRenderer, levelExtractor, world);
            ResourceManager resourceManager = client.getResourceManager();
            levelExtractor.onResourceManagerReload(resourceManager);
            ((IECloudRenderer) levelRenderer.cloudRenderer()).ip_reloadNow(resourceManager);
            Helper.log("Created renderer for " + world.dimension().identifier());
            return new DimensionRenderHelper(
                world, levelRenderer, levelExtractor, new Lightmap(), false
            );
        }

        public void discard() {
            levelRenderer.close();
        }
    }

    /**
     * Set the extractor's level with this dimension's renderer temporarily made current:
     * other mods attach per-renderer state to the level through {@code Minecraft.levelRenderer}
     * in {@link LevelExtractor#setLevel} (e.g. Sodium binds its world renderer this way).
     */
    private static void setExtractorLevel(
        LevelRenderer levelRenderer, LevelExtractor levelExtractor, ClientLevel level
    ) {
        LevelRenderer oldLevelRenderer = client.levelRenderer;
        LevelExtractor oldLevelExtractor = client.levelExtractor;
        ((IEMinecraftClient) client).ip_setLevelRendererAndExtractor(levelRenderer, levelExtractor);
        try {
            levelExtractor.setLevel(level);
        }
        finally {
            ((IEMinecraftClient) client).ip_setLevelRendererAndExtractor(oldLevelRenderer, oldLevelExtractor);
        }
    }

    /**
     * Whether Minecraft currently uses this dimension's renderer (vanilla then resizes/ends/closes it by itself).
     */
    public boolean isCurrent() {
        return client.levelRenderer == levelRenderer;
    }
    
    public void onResize(int width, int height) {
        if (!isCurrent()) {
            levelRenderer.resize(width, height);
        }
        if (farHelper != null) {
            farHelper.onResize(width, height);
        }
    }
    
    public void onEndFrame() {
        if (!isCurrent()) {
            levelRenderer.endFrame();
        }
        if (farHelper != null) {
            if (RenderStates.frameIndex - farHelper.lastUsedFrame > FAR_HELPER_IDLE_FRAMES) {
                releaseFarHelper();
            }
            else {
                farHelper.onEndFrame();
            }
        }
    }
    
    // vanilla only registers its first instances as reload listeners; reloading twice is harmless
    public void onResourceReload(ResourceManager resourceManager) {
        levelExtractor.onResourceManagerReload(resourceManager);
        ((IECloudRenderer) levelRenderer.cloudRenderer()).ip_reloadNow(resourceManager);
        if (farHelper != null) {
            farHelper.onResourceReload(resourceManager);
        }
    }

    public void onAllChanged() {
        if (!isCurrent()) {
            levelExtractor.allChanged();
        }
        if (farHelper != null) {
            farHelper.levelExtractor.allChanged();
        }
    }
    
    /**
     * Before cleaning up, the client must be switched back to the vanilla original instances
     * (see ClientWorldLoader.cleanUp), so that vanilla keeps using its own objects for the next level.
     */
    public void cleanUp() {
        releaseFarHelper();
        if (!isVanillaOriginal) {
            setExtractorLevel(levelRenderer, levelExtractor, null);
            levelRenderer.close();
            if (ownsLightmap) {
                lightmap.close();
            }
        }
    }
}
