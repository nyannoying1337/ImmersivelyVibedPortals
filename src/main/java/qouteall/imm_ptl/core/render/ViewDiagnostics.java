package qouteall.imm_ptl.core.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per portal view terrain diagnostics, to tell apart why terrain is missing in a view:
 * <ul>
 *     <li>{@code noChunk}: visible sections whose chunk the client doesn't have (chunk loading),</li>
 *     <li>{@code waitNeighbors} / {@code waitLight}: never built sections that vanilla doesn't build yet because a neighbor
 *     chunk is missing / a neighbor column has no light yet (normal at the edge of the loaded area; persistent ones in
 *     the middle mean that data never arrived),</li>
 *     <li>{@code uncompiled}: visible sections that were never built although their chunk and the 8 neighbor chunks are
 *     loaded and lit (section compiling),</li>
 *     <li>{@code gridMoves}: how often the renderers' ViewArea grids moved this frame (each move resets the sections
 *     that move in the grid; moving back and forth every frame keeps them from ever being built),</li>
 *     <li>none of these but terrain missing: the sections are drawn wrong (rendering / graphics backend).</li>
 * </ul>
 * With Sodium, whose renderer replaces the vanilla section lists, the counts above are not available; instead:
 * {@code sodiumGeometry}, the visible sections that have geometry, and {@code sodiumPending}, Sodium's pending tasks
 * of the view (builds, but also rebuilds and resorts of sections that are shown).
 * Off by default (it walks all visible sections of every view). Used by the tests and
 * {@code /imm_ptl_client_debug report_portal_views}.
 */
@Environment(EnvType.CLIENT)
public class ViewDiagnostics {
    public static boolean enabled = false;

    public record ViewReport(
        String description, String dimension, Vec3 cameraPos, @Nullable SectionPos gridCenter, int gridRadius,
        // the view's render distance in chunks (limited to the terrain loaded behind the portal, LoadedTerrainRadius)
        int renderDistance,
        int visible, int noChunk, int waitNeighbors, int waitLight, int uncompiled,
        // -1 without Sodium
        int sodiumGeometry, int sodiumPending
    ) {
        public boolean isSodium() {
            return sodiumGeometry >= 0;
        }

        /**
         * No terrain, or terrain that could be built but isn't.
         */
        public boolean hasProblem() {
            if (isSodium()) {
                // Sodium's pending builds also include rebuilds and resorts of sections that are shown fine
                // (views of the main view's dimension keep some pending), so only an empty view counts
                return sodiumGeometry == 0;
            }
            return visible == 0 || uncompiled > 0;
        }

        @Override
        public String toString() {
            if (isSodium()) {
                return String.format(Locale.ROOT,
                    "%s in %s camera (%.1f %.1f %.1f) render distance %d, Sodium: sections with geometry %d, pending builds %d",
                    description, dimension, cameraPos.x, cameraPos.y, cameraPos.z, renderDistance, sodiumGeometry, sodiumPending
                );
            }
            return String.format(Locale.ROOT,
                "%s in %s camera (%.1f %.1f %.1f) render distance %d, grid center %s radius %d: visible %d, no chunk %d, wait neighbors %d, wait light %d, uncompiled %d",
                description, dimension, cameraPos.x, cameraPos.y, cameraPos.z, renderDistance,
                gridCenter == null ? "none" : gridCenter.x() + " " + gridCenter.y() + " " + gridCenter.z(), gridRadius,
                visible, noChunk, waitNeighbors, waitLight, uncompiled
            );
        }
    }

    private static List<ViewReport> currentFrame = new ArrayList<>();
    private static int currentGridMoves = 0;
    private static List<ViewReport> lastFrame = List.of();
    private static int lastFrameGridMoves = 0;

    public static List<ViewReport> getLastFrame() {
        return lastFrame;
    }

    public static int getLastFrameGridMoves() {
        return lastFrameGridMoves;
    }

    /**
     * Called right after a portal view is rendered, with the view's state active.
     */
    public static void recordView(String description, ClientLevel level, LevelRenderer levelRenderer, Vec3 cameraPos) {
        if (!enabled) {
            return;
        }
        int[] sodiumStats = SodiumInterface.invoker.getViewSectionStats();
        if (sodiumStats != null) {
            currentFrame.add(new ViewReport(
                description, level.dimension().identifier().toString(), cameraPos, null, 0,
                WorldRenderInfo.getRenderDistance(), 0, 0, 0, 0, 0, sodiumStats[0], sodiumStats[1]
            ));
            return;
        }
        int visible = 0;
        int noChunk = 0;
        int waitNeighbors = 0;
        int waitLight = 0;
        int uncompiled = 0;
        for (SectionRenderDispatcher.RenderSection section : levelRenderer.visibleSections()) {
            visible++;
            long node = section.getSectionNode();
            int chunkX = SectionPos.x(node);
            int chunkZ = SectionPos.z(node);
            if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                noChunk++;
            }
            else if (section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED) {
                switch (neighborState(level, chunkX, chunkZ)) {
                    case 0 -> uncompiled++;
                    case 1 -> waitNeighbors++;
                    default -> waitLight++;
                }
            }
        }
        ViewArea viewArea = levelRenderer.viewArea();
        currentFrame.add(new ViewReport(
            description, level.dimension().identifier().toString(), cameraPos,
            viewArea == null ? null : viewArea.getCameraSectionPos(),
            viewArea == null ? 0 : viewArea.getViewDistance(),
            WorldRenderInfo.getRenderDistance(),
            visible, noChunk, waitNeighbors, waitLight, uncompiled, -1, -1
        ));
    }

    /**
     * Vanilla's condition for building a never built section (SectionUpdateTracker.hasAllNeighbors):
     * 0 if the 8 neighbor chunks are loaded and lit, 1 if one is missing, 2 if all are loaded but one has no light.
     */
    private static int neighborState(ClientLevel level, int chunkX, int chunkZ) {
        boolean unlit = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (level.getChunk(chunkX + dx, chunkZ + dz, ChunkStatus.FULL, false) == null) {
                    return 1;
                }
                if (!level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(chunkX + dx, chunkZ + dz))) {
                    unlit = true;
                }
            }
        }
        return unlit ? 2 : 0;
    }

    public static void onGridMoved() {
        currentGridMoves++;
    }

    public static void onEndFrame() {
        lastFrame = currentFrame;
        lastFrameGridMoves = currentGridMoves;
        currentFrame = new ArrayList<>();
        currentGridMoves = 0;
    }
}
