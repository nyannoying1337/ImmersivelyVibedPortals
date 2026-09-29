package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.miscellaneous.IPortalInitialScreen;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
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
            // (the gametest framework turns clouds off)
            mc.options.cloudStatus().set(CloudStatus.FANCY);
        });
        if (Boolean.getBoolean("imm_ptl.visualTest.shaders")) {
            // Iris with the test shaderpack (-PwithShaders)
            ctx.runOnClient(mc -> IrisTestPack.enable());
        }
        ctx.waitForScreen(TitleScreen.class);
        // -Dimm_ptl.visualTest.skip=true runs only the other tests (after the setup above,
        // so that the test still ends on the title screen)
        if (Boolean.getBoolean("imm_ptl.visualTest.skip")) {
            return;
        }

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
                "fill -8 -60 46 9 -52 46 minecraft:lime_concrete",
                // front clipping check: this block is between the front-side view cameras and the
                // destination portal plane, so it must never show up inside the portal from the front side
                "setblock 1 -59 41 minecraft:red_concrete"
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

            // third person: the player stands in front of the portal facing away from it, the camera behind the
            // player is on the other side of the portal plane, so the whole view must be the overworld seen from
            // the transformed camera: the frame's overworld side up close, and the player in the nether through it
            ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
            shoot(ctx, srv, new Pose("thirdperson_behind_portal", 1.0, 65.0, 2.0, 0, 0));
            ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));

            shootGlobalPortalAndMirror(ctx, sp, srv);
            shootClouds(ctx, sp, srv);
            shootEntityClipping(ctx, srv);

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

    /**
     * Global portal (not an entity; used for world wrapping and dimension stacks): nether x=-5,
     * opening z -4.5..-1.5, y 65..68, front side +X, maps nether (x,y,z) -> overworld (x+17, y-125, z+43).
     * Looking -X through it: overworld grass and sky, the overworld obsidian frame ahead,
     * the lime wall on the left and the light blue wall on the right. From behind it's invisible.
     * <p>
     * Mirror: nether x=6, opening z -5.5..-2.5, y 65..68, facing -X. Looking +X at it shows the nether
     * reflected: the orange wall stays on the LEFT (-Z), and the global portal (with the overworld)
     * behind the camera appears in it. Orange on the right means the reflection is flipped wrongly.
     */
    void shootGlobalPortalAndMirror(ClientGameTestContext ctx, TestSingleplayerContext sp, TestServerContext srv) {
        srv.runOnServer(s -> {
            ServerLevel nether = s.getLevel(Level.NETHER);
            Portal g = Portal.ENTITY_TYPE.create(nether, EntitySpawnReason.COMMAND);
            g.setOriginPos(new Vec3(-5.0, 66.5, -3.0));
            g.setOrientationAndSize(new Vec3(0, 0, -1), new Vec3(0, 1, 0), 3, 3);
            g.setDestinationDimension(Level.OVERWORLD);
            g.setDestination(new Vec3(12.0, -58.5, 40.0));
            PortalAPI.addGlobalPortal(nether, g);

            Mirror m = Mirror.ENTITY_TYPE.create(nether, EntitySpawnReason.COMMAND);
            m.setOriginPos(new Vec3(6.0, 66.5, -4.0));
            m.setOrientationAndSize(new Vec3(0, 0, 1), new Vec3(0, 1, 0), 3, 3);
            m.setDestinationDimension(Level.NETHER);
            m.setDestination(m.getOriginPos());
            McHelper.spawnServerEntity(m);
        });
        ctx.waitTicks(10);
        waitViews(ctx, sp, new Vec3(12.0, -58.5, 40.0));

        for (Pose p : List.of(
            new Pose("global_near", -2.0, 65.0, -3.0, 90, 0),
            new Pose("global_far", 3.0, 65.0, -3.0, 90, 0),
            new Pose("global_back", -8.0, 65.0, -3.0, -90, 0),
            new Pose("mirror_front", 3.0, 65.0, -4.0, -90, 0),
            new Pose("mirror_diag", 2.0, 65.0, -1.0, -110, 0),
            new Pose("mirror_close", 5.0, 65.0, -4.0, -90, 10)
        )) {
            shoot(ctx, srv, p);
        }

        // remove them, so that the walk sequence sees the same scene as before
        srv.runOnServer(s -> {
            ServerLevel nether = s.getLevel(Level.NETHER);
            for (Portal p : List.copyOf(GlobalPortalStorage.getGlobalPortals(nether))) {
                PortalAPI.removeGlobalPortal(nether, p);
            }
            nether.getEntitiesOfClass(Mirror.class, new AABB(0, 60, -10, 10, 72, 0)).forEach(Entity::discard);
        });
        ctx.waitTicks(10);
    }

    /**
     * Cloud portal: nether x 4..6, y 65..68, plane z=0.5 (right next to the nether portal, same facing +Z),
     * to the overworld at y~188, just below the clouds (bottom ~y=192). Looking -Z from the front,
     * the nether portal (left, x 0..2) and the cloud portal (right) both show the overworld,
     * so two views share the overworld's LevelRenderer. Expected: the cloud portal shows sky with
     * white cloud blocks overhead; the nether portal shows the overworld scene as before.
     */
    void shootClouds(ClientGameTestContext ctx, TestSingleplayerContext sp, TestServerContext srv) {
        srv.runOnServer(s -> {
            ServerLevel nether = s.getLevel(Level.NETHER);
            Portal p = Portal.ENTITY_TYPE.create(nether, EntitySpawnReason.COMMAND);
            p.setOriginPos(new Vec3(5.0, 66.5, 0.5));
            p.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 2, 3);
            p.setDestinationDimension(Level.OVERWORLD);
            p.setDestination(new Vec3(5.0, 188.0, 60.5));
            McHelper.spawnServerEntity(p);
        });
        ctx.waitTicks(10);
        waitViews(ctx, sp, new Vec3(5.0, 188.0, 60.5));

        for (Pose p : List.of(
            new Pose("clouds_both", 3.0, 65.0, 5.0, 180, -15),
            new Pose("clouds_close", 5.0, 65.0, 1.5, 180, -30)
        )) {
            shoot(ctx, srv, p);
        }

        srv.runOnServer(s -> s.getLevel(Level.NETHER)
            .getEntitiesOfClass(Portal.class, new AABB(3.5, 60, 0, 6.5, 72, 1))
            .forEach(Entity::discard));
        ctx.waitTicks(10);
    }

    /**
     * Entity halfway through the portal: a cow at z=0.6 facing -Z, its head through the plane z=0.5.
     * Front (looking -Z): the cow's back half in the nether, its head inside the portal (in the overworld view),
     * nothing of it doubled or missing at the plane.
     * Behind (looking +Z through the other face of the portal): no cow at all. The part that went through
     * is in the overworld, the rest is behind the portal surface. Cow parts in front of the portal are a bug
     * (the part of the entity that went through is not clipped).
     * <p>
     * Then a cow in the overworld destination frame, facing +Z, its head through the plane z=40.5 (towards the
     * camera of the portal view). Cross-portal entity rendering is turned off for this shot, so only the
     * portal view's clip plane cuts the cow. Front (entity_front_ow): its body inside the portal, no head.
     * A head inside the portal means the entity shader (vanilla, or the shaderpack's entity program) is not clipped.
     */
    void shootEntityClipping(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand(NETHER + "summon minecraft:cow 1.0 65.0 0.6 {NoAI:1b,Silent:1b,Rotation:[180f,0f],Tags:[\"immptl_test\"]}");
        ctx.waitTicks(20);
        for (Pose p : List.of(
            new Pose("entity_behind", 1.0, 65.0, -2.5, 0, 15),
            new Pose("entity_behind_diag", -1.0, 65.0, -2.0, -35, 15),
            // (end on the front side: the walk sequence teleports from here with teleportation enabled)
            new Pose("entity_front", 1.0, 65.0, 3.5, 180, 15),
            new Pose("entity_front_diag", 3.0, 65.0, 3.0, 145, 15)
        )) {
            shoot(ctx, srv, p);
        }
        removeTestEntities(srv);

        srv.runCommand("summon minecraft:cow 1.0 -60.0 40.3 {NoAI:1b,Silent:1b,Rotation:[0f,0f],Tags:[\"immptl_test\"]}");
        ctx.runOnClient(mc -> IPGlobal.correctCrossPortalEntityRendering = false);
        ctx.waitTicks(20);
        shoot(ctx, srv, new Pose("entity_front_ow", 1.0, 65.0, 3.5, 180, 10));
        ctx.runOnClient(mc -> IPGlobal.correctCrossPortalEntityRendering = true);
        removeTestEntities(srv);
        ctx.waitTicks(10);
    }

    // remove them at once (a killed mob stays for the death animation and pushes the player in the walk sequence)
    static void removeTestEntities(TestServerContext srv) {
        srv.runOnServer(s -> {
            s.getLevel(Level.NETHER)
                .getEntitiesOfClass(Entity.class, FRAME, e -> e.entityTags().contains("immptl_test"))
                .forEach(Entity::discard);
            s.getLevel(Level.OVERWORLD)
                .getEntitiesOfClass(Entity.class, new AABB(-2, -62, 38, 4, -55, 45), e -> e.entityTags().contains("immptl_test"))
                .forEach(Entity::discard);
        });
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
            // diagnostics: section visibility of the main view's renderer and the overworld renderer
            var main = mc.levelRenderer;
            var other = ClientWorldLoader.getWorldRenderer(
                mc.level.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER
            );
            return String.format(
                Locale.ROOT, "%s,%s,%.5f,%.5f,%.5f,%.1f,%.1f,mainVisible=%d,mainNearby=%d,mainRendered=%d,mainAllDone=%b,otherVisible=%d%n",
                p.name, mc.level.dimension().identifier(), c.x, c.y, c.z,
                mc.player.getYRot(), mc.player.getXRot(),
                main.visibleSections().size(), main.nearbyVisibleSections().size(),
                mc.levelExtractor.countRenderedSections(), main.hasRenderedAllSections(),
                other.visibleSections().size()
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
