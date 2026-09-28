package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.miscellaneous.IPortalInitialScreen;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalManipulation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Visual test: screenshots of a nether -> overworld portal from fixed camera poses,
 * especially with the camera at or very close to the portal plane.
 * Run: ./gradlew runClientGameTest [-PipBackend=opengl|vulkan]
 * Output: build/visual-test/<backend>/<pose>.png and poses.csv
 * <p>
 * Scene. Nether: portal opening x 0..2, y 65..68 in an obsidian frame at z=0, portal plane z=0.5,
 * front side (normal) +Z. Marker walls: magenta concrete at z=+8, orange concrete at z=-8,
 * netherrack floor at y=63.
 * Overworld (flat): destination frame at z=40 (plane z=40.5), marker walls: light blue concrete at z=34,
 * lime concrete at z=46.
 * Expected: from the front side (z>0.5) looking -Z, the portal shows the overworld with the light blue wall;
 * from the back side (z<0.5) looking +Z, the overworld with the lime wall.
 * With the eye on the plane looking along it (+X), the screen splits at the plane line:
 * the half towards -Z/+Z beyond the plane shows the overworld, the other half the nether.
 * A flat single-colour region, or overworld colours outside the opening, is a bug.
 */
public class PortalVisualTest implements FabricClientGameTest {
    record Pose(String name, double x, double y, double z, float yaw, float pitch) {}

    static final String NETHER = "execute in minecraft:the_nether run ";
    static final AABB FRAME = new AABB(-2, 63, -2, 4, 70, 3);

    private Path out;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        out = Path.of(System.getProperty("imm_ptl.visualTest.out", "visual-test"));
        try {
            Files.createDirectories(out);
            Files.deleteIfExists(out.resolve("poses.csv"));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ctx.runOnClient(mc -> {
            IPConfig c = IPConfig.getConfig();
            c.initialScreenShown = true;
            c.enableClientPerformanceAdjustment = false;
            c.enableServerPerformanceAdjustment = false;
            c.checkModInfoFromInternet = false;
            c.enableUpdateNotification = false;
            c.saveConfigFile();
            if (mc.gui.screen() instanceof IPortalInitialScreen s) {
                s.onClose();
            }
            mc.options.bobView().set(false);
            mc.options.fov().set(70);
            mc.options.renderDistance().set(8);
        });
        ctx.waitForScreen(TitleScreen.class);

        try (TestSingleplayerContext sp = ctx.worldBuilder()
            .adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
            .create()
        ) {
            TestServerContext srv = sp.getServer();
            sp.getConnection().waitForChunksRender();
            srv.runCommand("time set noon");

            // overworld destination frame and marker walls (flat world surface is at y=-60)
            for (String c : List.of(
                "fill -1 -61 40 2 -57 40 minecraft:obsidian",
                "fill 0 -60 40 1 -58 40 minecraft:air",
                "fill -8 -60 34 9 -52 34 minecraft:light_blue_concrete",
                "fill -8 -60 46 9 -52 46 minecraft:lime_concrete"
            )) {
                srv.runCommand(c);
            }

            srv.runCommand(NETHER + "portal tp Player0 1.0 65.0 -5.0");
            ctx.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.NETHER, 600);
            srv.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> {
                p.getAbilities().flying = true;
                p.onUpdateAbilities();
            }));
            // (Fabric's waitForChunksRender checks vanilla's chunk cache view center, which ImmPtl's
            // seamless teleport doesn't update, so wait for the section compiles instead)
            waitIdle(ctx);

            // nether arena, frame and marker walls
            for (String c : List.of(
                "fill -8 63 -8 9 63 8 minecraft:netherrack",
                "fill -8 64 -8 9 73 8 minecraft:air",
                "fill -8 64 8 9 73 8 minecraft:magenta_concrete",
                "fill -8 64 -8 9 73 -8 minecraft:orange_concrete",
                "fill -1 64 0 2 68 0 minecraft:obsidian",
                "fill 0 65 0 1 67 0 minecraft:air"
            )) {
                srv.runCommand(NETHER + c);
            }

            // deterministic bi-way, bi-faced portal (like a lit nether portal)
            srv.runOnServer(s -> {
                Portal p = Portal.ENTITY_TYPE.create(s.getLevel(Level.NETHER), EntitySpawnReason.COMMAND);
                p.setOriginPos(new Vec3(1.0, 66.5, 0.5));
                p.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 2, 3);
                p.setDestinationDimension(Level.OVERWORLD);
                p.setDestination(new Vec3(1.0, -58.5, 40.5));
                McHelper.spawnServerEntity(p);
                PortalManipulation.completeBiWayBiFacedPortal(p, x -> {}, x -> {}, Portal.ENTITY_TYPE);
            });
            Vec3 dest = srv.computeOnServer(s ->
                s.getLevel(Level.NETHER).getEntitiesOfClass(Portal.class, FRAME).get(0).getDestPos()
            );

            ctx.runOnClient(mc -> {
                IPGlobal.disableTeleportation = true;
                if (!mc.gui.hud.isHidden()) {
                    mc.gui.hud.toggle();
                }
            });

            // look through the portal so that the remote sections get loaded and compiled
            shoot(ctx, srv, new Pose("warmup", 1.0, 65.0, -2.0, 0, 0));
            waitViews(ctx, sp, dest);

            for (Pose p : poses()) {
                shoot(ctx, srv, p);
            }

            // walk through the plane with teleportation enabled
            ctx.runOnClient(mc -> IPGlobal.disableTeleportation = false);
            srv.runCommand(NETHER + "tp Player0 1.0 65.0 1.5 180.0 0.0");
            ctx.waitTicks(10);
            for (int i = 0; i < 20; i++) {
                ctx.runOnClient(mc -> mc.player.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ() - 0.1));
                ctx.waitTick();
                shoot(ctx, null, new Pose(String.format(Locale.ROOT, "walk_%02d", i), 0, 0, 0, 0, 0));
            }
        }
    }

    static void waitIdle(ClientGameTestContext ctx) {
        ctx.waitTicks(20);
        int[] idle = {0};
        ctx.waitFor(mc -> {
            idle[0] = mc.levelRenderer.hasRenderedAllSections() ? idle[0] + 1 : 0;
            return idle[0] >= 20;
        }, 2400);
    }
    
    static void waitViews(ClientGameTestContext ctx, TestSingleplayerContext sp, Vec3 d) {
        ctx.waitFor(mc -> {
            ClientLevel w = ClientWorldLoader.getOptionalWorld(Level.OVERWORLD);
            return w != null && w.getChunkSource().hasChunk(
                SectionPos.blockToSectionCoord(d.x), SectionPos.blockToSectionCoord(d.z)
            );
        }, 2400);
        int[] idle = {0};
        ctx.waitFor(mc -> {
            boolean done = mc.levelRenderer.hasRenderedAllSections()
                && ClientWorldLoader.getWorldRenderer(Level.OVERWORLD).hasRenderedAllSections();
            idle[0] = done ? idle[0] + 1 : 0;
            return idle[0] >= 40;
        }, 2400);
    }

    void shoot(ClientGameTestContext ctx, TestServerContext srv, Pose p) {
        if (srv != null) {
            // always print a decimal point: integer x/z get +0.5 centering
            srv.runCommand(String.format(
                Locale.ROOT, NETHER + "tp Player0 %.4f %.4f %.4f %.2f %.2f", p.x, p.y, p.z, p.yaw, p.pitch
            ));
            ctx.waitTicks(4);
        }
        ctx.runOnClient(mc -> mc.gui.toastManager().clear());
        ctx.takeScreenshot(TestScreenshotOptions.of(p.name).disableCounterPrefix().withDestinationDir(out));
        String row = ctx.computeOnClient(mc -> {
            Vec3 c = mc.gameRenderer.mainCamera().position();
            return String.format(
                Locale.ROOT, "%s,%s,%.5f,%.5f,%.5f,%.1f,%.1f%n",
                p.name, mc.level.dimension().identifier(), c.x, c.y, c.z,
                mc.player.getYRot(), mc.player.getXRot()
            );
        });
        try {
            Files.writeString(out.resolve("poses.csv"), row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Feet at y=65, eye at y~66.62. Yaw: 0 = +Z, -90 = +X, 90 = -X, 180 = -Z.
     * d = signed distance of the feet (and eye) from the portal plane z=0.5 (positive = front side).
     */
    static List<Pose> poses() {
        List<Pose> l = new ArrayList<>();
        for (double d : new double[]{-0.6, -0.11, -0.02, 0.02, 0.11, 0.6}) {
            String s = String.format(Locale.ROOT, "%+.2f", d);
            double z = 0.5 + d;
            l.add(new Pose("alongE" + s, 1.0, 65.0, z, -90, 0));
            l.add(new Pose("alongW" + s, 1.0, 65.0, z, 90, 0));
            l.add(new Pose("diagSE" + s, 1.0, 65.0, z, -135, 0));
            l.add(new Pose("diagNE" + s, 1.0, 65.0, z, -45, 0));
            l.add(new Pose("alongE_down" + s, 1.0, 65.0, z, -90, 45));
        }
        for (double d : new double[]{0.3, 1.0, 2.0}) {
            l.add(new Pose(String.format(Locale.ROOT, "thruS_%.1f", d), 1.0, 65.0, 0.5 + d, 180, 0));
            l.add(new Pose(String.format(Locale.ROOT, "thruN_down_%.1f", d), 1.0, 65.0, 0.5 - d, 20, 25));
        }
        return l;
    }
}
