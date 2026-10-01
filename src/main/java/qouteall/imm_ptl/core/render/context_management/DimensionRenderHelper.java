package qouteall.imm_ptl.core.render.context_management;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ducks.IEClientWorld;
import qouteall.imm_ptl.core.ducks.IECloudRenderer;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.ducks.IELevelRenderer_ViewGrid;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.q_misc_util.Helper;

import java.util.ArrayList;
import java.util.List;

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

    // false for an extra helper, which uses the lightmap of its dimension's helper
    private final boolean ownsLightmap;

    // see selectForView
    private final List<DimensionRenderHelper> extraHelpers = new ArrayList<>();
    private long lastUsedFrame;
    // the frame in which a portal view last used this helper's grid (it's not moved again in that frame)
    private long gridUsedFrame = -1;
    private static final int EXTRA_HELPER_IDLE_FRAMES = 600;
    private static final int MAX_EXTRA_HELPERS = 3;
    // a view camera less than this many sections from the edge of a grid doesn't use that grid
    private static final int GRID_MARGIN_SECTIONS = 4;


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
     * The helper to render a portal view of this dimension whose camera is at {@code cameraPos}.
     * <p>
     * A LevelRenderer's ViewArea is a fixed grid (the render distance around its center) and can only show the
     * sections in it. Moving the grid resets the sections that move in it, and views look up their sections before
     * vanilla would move it. So several views of one dimension far apart can't share one renderer: moving its grid
     * back and forth every frame, their sections were never built and they showed no terrain (or only the part in
     * both grids). So each dimension has this helper plus up to {@link #MAX_EXTRA_HELPERS} extra helpers (their own
     * renderer and extractor on the same ClientLevel, sharing the lightmap), and a view uses:
     * <ol>
     *     <li>a helper whose grid covers the camera already (no move),</li>
     *     <li>else the nearest helper that no view used yet in this frame, its grid moved to the camera now,
     *     before the view is extracted (or a new extra helper),</li>
     *     <li>else the nearest helper as it is (partial terrain rather than grids moving every frame).</li>
     * </ol>
     * The grid of the player's dimension's own helper is the main view's (centered on the player): it is never moved.
     * Extra helpers are released when they're not used for a while.
     */
    public DimensionRenderHelper selectForView(Vec3 cameraPos, boolean isPlayerDimension) {
        SectionPos cameraSection = SectionPos.of(cameraPos);
        long frame = RenderStates.frameIndex;

        List<DimensionRenderHelper> all = new ArrayList<>(extraHelpers.size() + 1);
        all.add(this);
        all.addAll(extraHelpers);

        DimensionRenderHelper result = null;
        for (DimensionRenderHelper helper : all) {
            if (helper.gridCovers(cameraSection)) {
                result = helper;
                break;
            }
        }

        if (result == null) {
            DimensionRenderHelper movable = null;
            for (DimensionRenderHelper helper : all) {
                boolean fixed = helper == this && isPlayerDimension;
                if (!fixed && helper.gridUsedFrame != frame
                    && (movable == null || helper.gridDistance(cameraSection) < movable.gridDistance(cameraSection))
                ) {
                    movable = helper;
                }
            }
            if (movable == null && extraHelpers.size() < MAX_EXTRA_HELPERS) {
                movable = createExtraHelper();
            }
            if (movable != null) {
                if (((IELevelRenderer_ViewGrid) movable.levelRenderer).ip_moveGridForView(cameraSection, cameraPos)) {
                    qouteall.imm_ptl.core.render.ViewDiagnostics.onGridMoved();
                }
                result = movable;
            }
        }

        if (result == null) {
            for (DimensionRenderHelper helper : all) {
                if (result == null || helper.gridDistance(cameraSection) < result.gridDistance(cameraSection)) {
                    result = helper;
                }
            }
        }

        result.gridUsedFrame = frame;
        result.lastUsedFrame = frame;
        return result;
    }

    private boolean gridCovers(SectionPos cameraSection) {
        ViewArea viewArea = levelRenderer.viewArea();
        if (viewArea == null) {
            return false;
        }
        SectionPos center = viewArea.getCameraSectionPos();
        int maxOffset = Math.max(viewArea.getViewDistance() - GRID_MARGIN_SECTIONS, 1);
        return Math.abs(cameraSection.x() - center.x()) <= maxOffset
            && Math.abs(cameraSection.z() - center.z()) <= maxOffset;
    }

    private int gridDistance(SectionPos cameraSection) {
        ViewArea viewArea = levelRenderer.viewArea();
        if (viewArea == null) {
            return Integer.MAX_VALUE;
        }
        SectionPos center = viewArea.getCameraSectionPos();
        return Math.max(Math.abs(cameraSection.x() - center.x()), Math.abs(cameraSection.z() - center.z()));
    }

    private DimensionRenderHelper createExtraHelper() {
        Pending pending = createNew();
        setExtractorLevel(pending.levelRenderer(), pending.levelExtractor(), world);
        ResourceManager resourceManager = client.getResourceManager();
        pending.levelExtractor().onResourceManagerReload(resourceManager);
        ((IECloudRenderer) pending.levelRenderer().cloudRenderer()).ip_reloadNow(resourceManager);
        DimensionRenderHelper extra = new DimensionRenderHelper(
            world, pending.levelRenderer(), pending.levelExtractor(), lightmap, false, false
        );
        extraHelpers.add(extra);
        updateExtraExtractors();
        Helper.log("Created extra view renderer " + extraHelpers.size() + " for " + world.dimension().identifier());
        return extra;
    }

    // the level forwards block changes to the extra extractors, see IEClientWorld.ip_setExtraExtractors
    private void updateExtraExtractors() {
        ((IEClientWorld) world).ip_setExtraExtractors(
            extraHelpers.stream().map(helper -> helper.levelExtractor).toList()
        );
    }

    /**
     * If the renderer is an extra helper's (see selectForView), the renderer of the helper it belongs to.
     * All extractors of a level take the section and chunk load changes from the same ClientLevel, and each change is
     * given out once, so an extra helper's extraction passes them on to that renderer (see MixinLevelRenderer).
     */
    public static @Nullable LevelRenderer getExtraHelperOwnerRenderer(LevelRenderer levelRenderer) {
        for (DimensionRenderHelper helper : qouteall.imm_ptl.core.ClientWorldLoader.RENDER_HELPER_MAP.values()) {
            for (DimensionRenderHelper extra : helper.extraHelpers) {
                if (extra.levelRenderer == levelRenderer) {
                    return helper.levelRenderer;
                }
            }
        }
        return null;
    }

    private void releaseExtraHelper(DimensionRenderHelper extra) {
        extraHelpers.remove(extra);
        updateExtraExtractors();
        extra.cleanUp();
        Helper.log("Released an extra view renderer for " + world.dimension().identifier());
    }

    private void releaseExtraHelpers() {
        for (DimensionRenderHelper extra : new ArrayList<>(extraHelpers)) {
            releaseExtraHelper(extra);
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
        for (DimensionRenderHelper extra : extraHelpers) {
            extra.onResize(width, height);
        }
    }
    
    public void onEndFrame() {
        if (!isCurrent()) {
            levelRenderer.endFrame();
        }
        for (DimensionRenderHelper extra : new ArrayList<>(extraHelpers)) {
            if (RenderStates.frameIndex - extra.lastUsedFrame > EXTRA_HELPER_IDLE_FRAMES) {
                releaseExtraHelper(extra);
            }
            else {
                extra.onEndFrame();
            }
        }
    }
    
    // vanilla only registers its first instances as reload listeners; reloading twice is harmless
    public void onResourceReload(ResourceManager resourceManager) {
        levelExtractor.onResourceManagerReload(resourceManager);
        ((IECloudRenderer) levelRenderer.cloudRenderer()).ip_reloadNow(resourceManager);
        for (DimensionRenderHelper extra : extraHelpers) {
            extra.onResourceReload(resourceManager);
        }
    }

    public void onAllChanged() {
        if (!isCurrent()) {
            levelExtractor.allChanged();
        }
        for (DimensionRenderHelper extra : extraHelpers) {
            extra.levelExtractor.allChanged();
        }
    }
    
    /**
     * Before cleaning up, the client must be switched back to the vanilla original instances
     * (see ClientWorldLoader.cleanUp), so that vanilla keeps using its own objects for the next level.
     */
    public void cleanUp() {
        releaseExtraHelpers();
        if (!isVanillaOriginal) {
            setExtractorLevel(levelRenderer, levelExtractor, null);
            levelRenderer.close();
            if (ownsLightmap) {
                lightmap.close();
            }
        }
    }
}
