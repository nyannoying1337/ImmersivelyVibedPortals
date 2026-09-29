package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.miscellaneous.IPortalInitialScreen;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.EndPortalEntity;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Functional smoke tests for features that go through real game code paths:
 * lighting a nether portal frame, walking through it both ways, end portals,
 * /portal commands, world wrapping and dimension stacks (global portals),
 * and the same nether portal on a dedicated server (multiplayer networking).
 * <p>
 * Every check is recorded in build/feature-test/report.txt; the test fails at the end if any check failed.
 * Screenshots of each scene are written next to the report.
 * Run: ./gradlew runClientGameTest (together with the visual test).
 */
public class FeatureSmokeTest implements FabricClientGameTest {
    private final List<String> results = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private Path out;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        out = Path.of(System.getProperty("imm_ptl.featureTest.out", "feature-test"));
        try {
            Files.createDirectories(out);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ctx.runOnClient(mc -> {
            IPConfig c = IPConfig.getConfig();
            c.initialScreenShown = true;
            c.saveConfigFile();
            if (mc.gui.screen() instanceof IPortalInitialScreen s) {
                s.onClose();
            }
            mc.options.bobView().set(false);
            if (!mc.gui.hud.isHidden()) {
                mc.gui.hud.toggle();
            }
            IPGlobal.disableTeleportation = false;
        });
        ctx.waitForScreen(TitleScreen.class);

        try (TestSingleplayerContext sp = ctx.worldBuilder()
            .adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
            .create()
        ) {
            TestServerContext srv = sp.getServer();
            sp.getConnection().waitForChunksRender();
            srv.runCommand("time set noon");
            srv.runCommand("gamerule doDaylightCycle false");
            setFlying(srv, true);

            section("singleplayer nether portal", () -> netherPortal(ctx, srv, "sp"));
            section("end portal", () -> endPortal(ctx, srv));
            section("make_portal command", () -> makePortalCommand(ctx, srv));
            section("world wrapping", () -> worldWrapping(ctx, srv));
            section("dimension stack", () -> dimensionStack(ctx, srv));

            // global portals for the migration check below
            srv.runCommand("portal global create_inward_wrapping -40 -40 40 40");
            ctx.waitTicks(20);
            migrationPortalCount = srv.computeOnServer(s -> GlobalPortalStorage.getGlobalPortals(s.overworld()).size());
            worldSave = sp.getWorldSave();
        }

        section("migration of old global portal files", () -> migration(ctx));

        // Multiplayer: a local dedicated server in the test's run directory (build/run/clientGameTest).
        // It needs eula=true in its eula.txt (the Minecraft EULA; the project owner accepted it for this
        // test server). Disable with -Dimm_ptl.featureTest.dedicated=false.
        if (!"false".equals(System.getProperty("imm_ptl.featureTest.dedicated"))) section("dedicated server", () -> {
            try {
                Files.writeString(Path.of("eula.txt"), "eula=true\n");
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            try (TestDedicatedServerContext server = ctx.worldBuilder().createServer();
                 TestDedicatedServerConnection connection = server.connect()
            ) {
                connection.waitForChunksRender();
                server.runCommand("op Player0");
                server.runCommand("time set noon");
                server.runCommand("gamemode creative Player0");
                ctx.waitTicks(10);
                setFlying(server, true);
                check("dedicated: client joined the overworld",
                    ctx.computeOnClient(mc -> mc.level != null && mc.level.dimension() == Level.OVERWORLD), "");
                netherPortal(ctx, server, "mp");
            }
        });

        writeReport();
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " feature checks failed:\n" + String.join("\n", failures));
        }
    }

    // ---- scenes ----

    /**
     * Light an obsidian frame with fire: the mod generates a see-through portal and its destination frame
     * in the nether. Then walk through it into the nether and back.
     */
    private void netherPortal(ClientGameTestContext ctx, TestServerContext srv, String tag) {
        int ground = groundY(srv, Level.OVERWORLD, 20, 20);
        int x0 = 20, z = 20;
        // frame: x 20..23, y ground..ground+4; opening x 21..22, y ground+1..ground+3
        srv.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:obsidian", x0, ground, z, x0 + 3, ground + 4, z));
        srv.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x0 + 1, ground + 1, z, x0 + 2, ground + 3, z));
        // a floor level with the bottom of the opening, so that walking through isn't blocked by the frame
        srv.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x0, ground, z + 1, x0 + 3, ground, z + 4));
        srv.runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:fire", x0 + 1, ground + 1, z));

        AABB frameBox = new AABB(x0 - 1, ground - 1, z - 2, x0 + 5, ground + 6, z + 3);
        boolean generated = waitFor(ctx, 1200, () -> srv.computeOnServer(s ->
            s.overworld().getEntitiesOfClass(Portal.class, frameBox).size() >= 2
        ));
        check(tag + ": lighting the frame created portals in the overworld", generated,
            "portals near frame: " + srv.computeOnServer(s -> s.overworld().getEntitiesOfClass(Portal.class, frameBox).size()));
        if (!generated) {
            return;
        }

        Portal portal = srv.computeOnServer(s -> s.overworld().getEntitiesOfClass(Portal.class, frameBox).get(0));
        Vec3 dest = portal.getDestPos();
        ResourceKey<Level> destDim = portal.getDestDim();
        check(tag + ": the portal leads to the nether", destDim == Level.NETHER, "dest dim " + destDim.identifier());

        boolean destPortals = waitFor(ctx, 600, () -> srv.computeOnServer(s ->
            !s.getLevel(Level.NETHER).getEntitiesOfClass(Portal.class, new AABB(dest, dest).inflate(3)).isEmpty()
        ));
        check(tag + ": the nether side has portals at the destination", destPortals, "dest " + dest);
        boolean destFrame = srv.computeOnServer(s -> {
            ServerLevel nether = s.getLevel(Level.NETHER);
            BlockPos c = BlockPos.containing(dest);
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-3, -3, -3), c.offset(3, 3, 3))) {
                if (nether.getBlockState(p).is(Blocks.OBSIDIAN)) {
                    return true;
                }
            }
            return false;
        });
        check(tag + ": an obsidian frame exists at the nether destination", destFrame, "dest " + dest);

        // look through the portal: the client must have loaded the nether around the destination
        srv.runCommand(String.format(Locale.ROOT, "tp Player0 %.1f %d %.1f 180 0", x0 + 2.0, ground + 1, z + 4.0));
        boolean remoteLoaded = waitFor(ctx, 1200, () -> ctx.computeOnClient(mc -> {
            ClientLevel nether = ClientWorldLoader.getOptionalWorld(Level.NETHER);
            return nether != null && nether.getChunkSource().hasChunk(
                BlockPos.containing(dest).getX() >> 4, BlockPos.containing(dest).getZ() >> 4
            );
        }));
        check(tag + ": the client loaded the nether chunks behind the portal", remoteLoaded, "");
        ctx.waitTicks(60);
        screenshot(ctx, tag + "_nether_portal");

        // walk through (feet at the frame's inside bottom, moving -Z through the plane)
        boolean inNether = walk(ctx, srv, x0 + 2.0, ground + 1, z + 1.5, 180, -1, Level.NETHER);
        check(tag + ": walking through the portal enters the nether", inNether, describe(ctx, srv, frameBox));
        if (!inNether) {
            return;
        }
        ctx.waitTicks(40);
        screenshot(ctx, tag + "_arrived_in_nether");

        // walk backwards through the same portal (the facing was transformed by the portal)
        boolean back = walkBackwards(ctx, Level.OVERWORLD);
        check(tag + ": walking back through the portal returns to the overworld", back,
            "client dim " + ctx.computeOnClient(mc -> mc.level.dimension().identifier()));
        ctx.waitTicks(20);
        screenshot(ctx, tag + "_back_in_overworld");
    }

    /**
     * Completing an end portal frame creates a see-through end portal; falling into it leads to the End.
     */
    private void endPortal(ClientGameTestContext ctx, TestServerContext srv) {
        int ground = groundY(srv, Level.OVERWORLD, 60, 20);
        Vec3 center = new Vec3(60.5, ground + 0.5, 20.5);
        srv.runOnServer(s -> EndPortalEntity.onEndPortalComplete(s.overworld(), center));
        ctx.waitTicks(10);
        boolean created = srv.computeOnServer(s ->
            !s.overworld().getEntitiesOfClass(EndPortalEntity.class, new AABB(center, center).inflate(4)).isEmpty()
        );
        check("end: completing the frame created an end portal entity", created, "");
        if (!created) {
            return;
        }

        srv.runCommand(String.format(Locale.ROOT, "tp Player0 %.1f %d %.1f 0 60", 60.5, ground + 6, 22.5));
        ctx.waitTicks(40);
        screenshot(ctx, "end_portal_from_above");

        srv.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> {
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
        }));
        srv.runCommand(String.format(Locale.ROOT, "tp Player0 %.1f %d %.1f 0 90", 60.5, ground + 3, 20.5));
        boolean inEnd = waitFor(ctx, 400, () -> ctx.computeOnClient(mc -> mc.level != null && mc.level.dimension() == Level.END));
        check("end: falling into the end portal enters the End", inEnd,
            "client dim " + ctx.computeOnClient(mc -> mc.level.dimension().identifier()));
        if (inEnd) {
            ctx.waitTicks(60);
            screenshot(ctx, "arrived_in_end");
        }

        // back to the overworld for the next scenes
        srv.runCommand("execute in minecraft:overworld run portal tp Player0 0 " + (ground + 1) + " 0");
        waitFor(ctx, 400, () -> ctx.computeOnClient(mc -> mc.level != null && mc.level.dimension() == Level.OVERWORLD));
        srv.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> {
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
        }));
    }

    /**
     * /portal make_portal places a portal where the player looks.
     */
    private void makePortalCommand(ClientGameTestContext ctx, TestServerContext srv) {
        int ground = groundY(srv, Level.OVERWORLD, 80, 20);
        srv.runCommand(String.format(Locale.ROOT, "fill 78 %d 16 82 %d 16 minecraft:stone", ground, ground + 4));
        srv.runCommand(String.format(Locale.ROOT, "tp Player0 80.5 %d 20.5 180 0", ground));
        ctx.waitTicks(10);
        AABB box = new AABB(76, ground - 2, 14, 85, ground + 6, 22);
        int before = srv.computeOnServer(s -> s.overworld().getEntitiesOfClass(Portal.class, box).size());
        srv.runCommand("execute as Player0 at Player0 anchored eyes run portal make_portal 2 3 minecraft:the_nether 0 100 0");
        ctx.waitTicks(10);
        List<Portal> portals = srv.computeOnServer(s -> s.overworld().getEntitiesOfClass(Portal.class, box));
        boolean ok = portals.size() == before + 1
            && portals.stream().anyMatch(p -> p.getDestDim() == Level.NETHER
            && p.getDestPos().distanceTo(new Vec3(0, 100, 0)) < 0.01);
        check("make_portal: a portal to the nether (0, 100, 0) was placed", ok, "portals " + portals);
        srv.runOnServer(s -> s.overworld().getEntitiesOfClass(Portal.class, box).forEach(p -> p.discard()));
    }

    /**
     * World wrapping: global portals around an area, synced to the client.
     */
    private void worldWrapping(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("portal global create_inward_wrapping -40 -40 40 40");
        ctx.waitTicks(20);
        int serverCount = srv.computeOnServer(s -> GlobalPortalStorage.getGlobalPortals(s.overworld()).size());
        check("wrapping: create_inward_wrapping created global portals", serverCount >= 4, "count " + serverCount);
        boolean synced = waitFor(ctx, 200, () -> ctx.computeOnClient(mc ->
            GlobalPortalStorage.getGlobalPortals(mc.level).size() == serverCount));
        check("wrapping: the global portals are synced to the client", synced, "");

        int ground = groundY(srv, Level.OVERWORLD, 30, 0);
        srv.runCommand(String.format(Locale.ROOT, "tp Player0 30.5 %d 0.5 -90 0", ground + 1));
        ctx.waitTicks(60);
        screenshot(ctx, "wrapping_border");

        srv.runOnServer(s -> GlobalPortalStorage.get(s.overworld()).removePortals(p -> true));
        ctx.waitTicks(20);
        check("wrapping: the global portals were removed", srv.computeOnServer(s ->
            GlobalPortalStorage.getGlobalPortals(s.overworld()).isEmpty()), "");
    }

    /**
     * Dimension stack: the overworld's floor leads to the nether.
     */
    private void dimensionStack(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("portal global connect_floor minecraft:overworld minecraft:the_nether");
        ctx.waitTicks(20);
        List<Portal> overworldPortals = srv.computeOnServer(s -> GlobalPortalStorage.getGlobalPortals(s.overworld()));
        boolean ok = overworldPortals.stream().anyMatch(p -> p.getDestDim() == Level.NETHER);
        check("dim stack: connect_floor created an overworld global portal to the nether", ok, "portals " + overworldPortals);
        srv.runOnServer(s -> {
            GlobalPortalStorage.get(s.overworld()).removePortals(p -> true);
            GlobalPortalStorage.get(s.getLevel(Level.NETHER)).removePortals(p -> true);
        });
        ctx.waitTicks(20);
    }

    private int migrationPortalCount;
    private TestWorldSave worldSave;

    /**
     * Worlds from before 26.x keep global portals in data/global_portal.dat; 26.x stores them in
     * data/immersive_portals/global_portal.dat. Move the saved files to the old location, reopen the
     * world, and check that the portals are migrated.
     */
    private void migration(ClientGameTestContext ctx) {
        check("migration: the world had global portals before closing", migrationPortalCount >= 4,
            "count " + migrationPortalCount);

        List<Path> moved = new ArrayList<>();
        try (var files = Files.walk(worldSave.getSaveDirectory())) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().equals("global_portal.dat")
                    && file.getParent().getFileName().toString().equals("immersive_portals")
                ) {
                    Path legacy = file.getParent().getParent().resolve(GlobalPortalStorage.LEGACY_FILE_NAME);
                    Files.move(file, legacy);
                    moved.add(legacy);
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        check("migration: found saved global portal files to move", !moved.isEmpty(), "");

        try (TestSingleplayerContext sp = worldSave.open()) {
            sp.getConnection().waitForChunksRender();
            TestServerContext srv = sp.getServer();
            int count = srv.computeOnServer(s -> GlobalPortalStorage.getGlobalPortals(s.overworld()).size());
            check("migration: the global portals were loaded from the old file", count == migrationPortalCount,
                "count " + count + ", expected " + migrationPortalCount);
            boolean synced = waitFor(ctx, 200, () -> ctx.computeOnClient(mc ->
                GlobalPortalStorage.getGlobalPortals(mc.level).size() == count));
            check("migration: the migrated portals are synced to the client", synced, "");
        }
    }

    // ---- helpers ----

    private static void setFlying(TestServerContext srv, boolean flying) {
        srv.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> {
            p.getAbilities().flying = flying;
            p.onUpdateAbilities();
        }));
    }

    /**
     * Diagnostics for a failed walk: the client player's position and the portals near it.
     */
    private static String describe(ClientGameTestContext ctx, TestServerContext srv, AABB portalBox) {
        String client = ctx.computeOnClient(mc -> String.format(Locale.ROOT,
            "client %s eye (%.3f, %.3f, %.3f) flying %b",
            mc.level.dimension().identifier(), mc.player.getEyePosition().x, mc.player.getEyePosition().y,
            mc.player.getEyePosition().z, mc.player.getAbilities().flying
        ));
        String portals = ctx.computeOnClient(mc -> mc.level.getEntitiesOfClass(Portal.class, portalBox).stream()
            .map(p -> String.format(Locale.ROOT, "%s origin %s normal %s %sx%s teleportable %b",
                p.getClass().getSimpleName(), p.getOriginPos(), p.getNormal(), p.getWidth(), p.getHeight(),
                p.isTeleportable()))
            .toList().toString());
        return client + "; client portals " + portals;
    }

    private int groundY(TestServerContext srv, ResourceKey<Level> dim, int x, int z) {
        return srv.computeOnServer(s -> {
            ServerLevel level = s.getLevel(dim);
            // make sure the column is loaded
            level.getChunk(x >> 4, z >> 4);
            return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
        });
    }

    /**
     * Teleport to the start, then move the client player step by step along Z until it's in the target dimension.
     */
    private boolean walk(
        ClientGameTestContext ctx, TestServerContext srv,
        double x, int y, double z, float yaw, int dirZ, ResourceKey<Level> target
    ) {
        srv.runCommand(String.format(Locale.ROOT, "tp Player0 %.2f %d %.2f %.1f 0", x, y, z, yaw));
        ctx.waitTicks(20);
        // no gravity while walking (the other side's floor may be at a different height)
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        });
        return walkRelative(ctx, 0, dirZ, target);
    }

    private boolean walkBackwards(ClientGameTestContext ctx, ResourceKey<Level> target) {
        Vec3 forward = ctx.computeOnClient(mc -> Vec3.directionFromRotation(0, mc.player.getYRot()));
        return walkAlong(ctx, forward.scale(-1), target);
    }

    private boolean walkRelative(ClientGameTestContext ctx, int dirX, int dirZ, ResourceKey<Level> target) {
        return walkAlong(ctx, new Vec3(dirX, 0, dirZ), target);
    }

    private boolean walkAlong(ClientGameTestContext ctx, Vec3 direction, ResourceKey<Level> target) {
        Vec3 step = direction.normalize().scale(0.1);
        for (int i = 0; i < 40; i++) {
            ctx.runOnClient(mc -> mc.player.setPos(
                mc.player.getX() + step.x, mc.player.getY(), mc.player.getZ() + step.z
            ));
            ctx.waitTick();
            if (ctx.computeOnClient(mc -> mc.level.dimension() == target)) {
                // let the server accept the teleport
                ctx.waitTicks(20);
                return true;
            }
        }
        return false;
    }

    private boolean waitFor(ClientGameTestContext ctx, int maxTicks, java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < maxTicks; i += 5) {
            if (condition.getAsBoolean()) {
                return true;
            }
            ctx.waitTicks(5);
        }
        return condition.getAsBoolean();
    }

    private void screenshot(ClientGameTestContext ctx, String name) {
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(out));
    }

    private void check(String name, boolean ok, String detail) {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() || ok ? "" : "  (" + detail + ")");
        results.add(line);
        if (!ok) {
            failures.add(line);
        }
    }

    private void section(String name, Runnable body) {
        results.add("== " + name);
        try {
            body.run();
        }
        catch (Throwable e) {
            check(name + ": finished without an exception", false, e.toString());
            java.io.StringWriter trace = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(trace));
            trace.toString().lines().limit(12).forEach(l -> results.add("    " + l));
        }
    }

    private void writeReport() {
        try {
            Files.write(out.resolve("report.txt"), results);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
