package qouteall.imm_ptl.core.render.context_management;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.server.packs.resources.ResourceManager;
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


    // the frame index at which the lightmap was last rendered, see RenderStates.frameIndex
    public long lightmapRenderedFrame = -1;

    private DimensionRenderHelper(
        ClientLevel world, LevelRenderer levelRenderer, LevelExtractor levelExtractor,
        Lightmap lightmap, boolean isVanillaOriginal
    ) {
        this.world = world;
        this.levelRenderer = levelRenderer;
        this.levelExtractor = levelExtractor;
        this.lightmap = lightmap;
        this.isVanillaOriginal = isVanillaOriginal;
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
    }
    
    public void onEndFrame() {
        if (!isCurrent()) {
            levelRenderer.endFrame();
        }
    }
    
    // vanilla only registers its first instances as reload listeners; reloading twice is harmless
    public void onResourceReload(ResourceManager resourceManager) {
        levelExtractor.onResourceManagerReload(resourceManager);
        ((IECloudRenderer) levelRenderer.cloudRenderer()).ip_reloadNow(resourceManager);
    }
    
    /**
     * Before cleaning up, the client must be switched back to the vanilla original instances
     * (see ClientWorldLoader.cleanUp), so that vanilla keeps using its own objects for the next level.
     */
    public void cleanUp() {
        if (!isVanillaOriginal) {
            setExtractorLevel(levelRenderer, levelExtractor, null);
            levelRenderer.close();
            lightmap.close();
        }
    }
}
