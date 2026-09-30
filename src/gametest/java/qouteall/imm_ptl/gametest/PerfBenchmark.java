package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.miscellaneous.IPortalInitialScreen;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.PortalViewRenderer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Portal rendering benchmark: a fixed camera looking at a row of N portals (N = 0, 1, 4, 8),
 * each leading to a different spot of the nether (so that every portal renders its own view).
 * Records wall-clock time per frame and CPU time spent rendering portal views.
 * <p>
 * In a client gametest the frames are paced by the test's tick stepping, so the absolute numbers are
 * not FPS; compare scenarios and builds with each other.
 * Only runs with -Dimm_ptl.perf=true. Output: build/perf/results.csv (appended).
 */
public class PerfBenchmark implements FabricClientGameTest {
    private static final int[] PORTAL_COUNTS = {0, 1, 4, 8};
    private static final int FRAMES = 300;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            IPConfig c = IPConfig.getConfig();
            c.initialScreenShown = true;
            c.enableClientPerformanceAdjustment = false;
            c.saveConfigFile();
            if (mc.gui.screen() instanceof IPortalInitialScreen s) {
                s.onClose();
            }
        });
        ctx.waitForScreen(TitleScreen.class);
        if (!Boolean.getBoolean("imm_ptl.perf")) {
            return;
        }

        Path out = Path.of(System.getProperty("imm_ptl.perf.out", "perf"));
        String label = System.getProperty("imm_ptl.perf.label", "run");
        try {
            Files.createDirectories(out);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(12);
            mc.options.bobView().set(false);
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            if (!mc.gui.hud.isHidden()) {
                mc.gui.hud.toggle();
            }
        });

        try (TestSingleplayerContext sp = ctx.worldBuilder()
            .adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
            .create()
        ) {
            TestServerContext srv = sp.getServer();
            sp.getConnection().waitForChunksRender();
            srv.runCommand("time set noon");
            srv.runCommand("gamerule doDaylightCycle false");
            srv.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> {
                p.getAbilities().flying = true;
                p.onUpdateAbilities();
            }));
            // camera looks north (-Z) at the row of portals at z = -6, x from -12 to 12
            srv.runCommand("tp Player0 0.5 -58 4.5 180 0");

            for (int n : PORTAL_COUNTS) {
                placePortals(srv, n, -6);
                // let the destination chunks load and compile
                ctx.waitTicks(200);
                measure(ctx, out, label, String.valueOf(n));
            }

            // 8 portals hidden behind blocks: the camera is in an air pocket in a solid 3x3x3 block of sections
            // (sections -1..1, -4..-3, -1..1), the portals are two sections away (PortalOcclusionCulling)
            placePortals(srv, 0, -6);
            for (int z = -16; z < 32; z += 16) {
                srv.runCommand(String.format(Locale.ROOT, "fill -16 -64 %d 31 -33 %d minecraft:stone", z, z + 15));
            }
            srv.runCommand("fill 0 -59 3 3 -55 6 minecraft:air");
            placePortals(srv, 8, -24);
            ctx.waitTicks(200);
            ctx.runOnClient(mc -> mc.levelRenderer.sectionOcclusionGraph().invalidate());
            ctx.waitTicks(20);
            measure(ctx, out, label, "8-hidden");
            ctx.runOnClient(mc -> qouteall.imm_ptl.core.IPCGlobal.cullHiddenPortals = false);
            ctx.waitTicks(20);
            measure(ctx, out, label, "8-hidden-no-culling");
            ctx.runOnClient(mc -> qouteall.imm_ptl.core.IPCGlobal.cullHiddenPortals = true);
        }
    }

    private static void measure(ClientGameTestContext ctx, Path out, String label, String scenario) {
        ctx.runOnClient(mc -> PortalViewRenderer.Stats.reset());
        long start = System.nanoTime();
        for (int i = 0; i < FRAMES; i++) {
            ctx.waitTick();
        }
        long wall = System.nanoTime() - start;
        String row = ctx.computeOnClient(mc -> String.format(Locale.ROOT,
            "%s,%s,%.3f,%.3f,%.2f%n",
            label, scenario,
            wall / 1e6 / FRAMES,
            PortalViewRenderer.Stats.frames == 0 ? 0 :
                PortalViewRenderer.Stats.nanos / 1e6 / PortalViewRenderer.Stats.frames,
            PortalViewRenderer.Stats.frames == 0 ? 0 :
                (double) PortalViewRenderer.Stats.views / PortalViewRenderer.Stats.frames
        ));
        try {
            Files.writeString(out.resolve("results.csv"), row,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void placePortals(TestServerContext srv, int n, double z) {
        srv.runOnServer(s -> {
            var level = s.overworld();
            level.getEntitiesOfClass(Portal.class, new AABB(-20, -70, -30, 20, -40, 0)).forEach(p -> p.discard());
            for (int i = 0; i < n; i++) {
                double x = n == 1 ? 0.5 : -10.5 + i * 3.0;
                Portal p = Portal.ENTITY_TYPE.create(level, EntitySpawnReason.COMMAND);
                p.setOriginPos(new Vec3(x, -57.5, z));
                // facing +Z (towards the camera)
                p.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 2, 3);
                p.setDestinationDimension(Level.NETHER);
                p.setDestination(new Vec3(i * 40.0, 70, 0));
                McHelper.spawnServerEntity(p);
            }
        });
    }
}
