package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
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
import java.util.Map;

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

    /**
     * gradle -Pimm_ptl.featureTest.userWorld=<save directory>: open a copy of that world (the test player is put at
     * imm_ptl.featureTest.userWorldPos: "dimension x y z yaw pitch") and record the portal view diagnostics there.
     */
    private void userWorld(ClientGameTestContext ctx, Path source, String pos) {
        TestWorldSave save;
        try (TestSingleplayerContext sp = ctx.worldBuilder()
            .adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
            .create()
        ) {
            save = sp.getWorldSave();
        }
        Path target = save.getSaveDirectory();
        try {
            try (var files = Files.walk(target)) {
                for (Path f : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    if (!f.equals(target)) {
                        Files.delete(f);
                    }
                }
            }
            try (var files = Files.walk(source)) {
                for (Path f : files.toList()) {
                    if (f.getFileName().toString().equals("session.lock")) {
                        continue;
                    }
                    Path t = target.resolve(source.relativize(f).toString());
                    if (Files.isDirectory(f)) {
                        Files.createDirectories(t);
                    }
                    else {
                        Files.copy(f, t);
                    }
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String[] p = pos.split(" ");
        // a render distance above indirectLoadingRadiusCap (8 chunks), like the reported case
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        try (TestSingleplayerContext sp = save.open()) {
            TestServerContext srv = sp.getServer();
            ctx.waitTicks(40);
            srv.runCommand("gamemode creative Player0");
            srv.runCommand(String.format(Locale.ROOT, "execute in %s run tp Player0 %s %s %s %s %s", p[0], p[1], p[2], p[3], p[4], p[5]));
            ctx.runOnClient(mc -> {
                qouteall.imm_ptl.core.render.ViewDiagnostics.enabled = true;
                if (!mc.gui.hud.isHidden()) {
                    mc.gui.hud.toggle();
                }
            });
            // -Pimm_ptl.featureTest.maxPortalLayer=<n>: limit the portal nesting (for narrowing down rendering issues)
            String maxLayer = System.getProperty("imm_ptl.featureTest.maxPortalLayer", "");
            if (!maxLayer.isEmpty()) {
                ctx.runOnClient(mc -> IPGlobal.maxPortalLayer = Integer.parseInt(maxLayer));
            }
            List<String> log = new ArrayList<>();
            log.add("render distance " + ctx.computeOnClient(mc -> mc.options.getEffectiveRenderDistance())
                + ", position " + pos + ", shaderpack in use "
                + ctx.computeOnClient(mc -> qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface.invoker.isShaderpackInUse())
                + ", max portal layer " + ctx.computeOnClient(mc -> IPGlobal.maxPortalLayer));
            String last = sampleTerrain(ctx, "user world", 6, 100, log);
            screenshot(ctx, "user_world");
            ctx.runOnClient(mc -> qouteall.imm_ptl.core.render.ViewDiagnostics.enabled = false);
            try {
                Files.write(out.resolve("user_world.txt"), log);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            check("user world: the portal views have terrain", last == null, String.valueOf(last));
        }
        ctx.waitForScreen(TitleScreen.class);
    }

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
            c.initialScreenVersion = qouteall.imm_ptl.core.miscellaneous.IPortalInitialScreen.CONTENT_VERSION;
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
        if (Boolean.getBoolean("imm_ptl.featureTest.skip")) {
            return;
        }
        String userWorld = System.getProperty("imm_ptl.featureTest.userWorld");
        if (userWorld != null && !userWorld.isEmpty()) {
            userWorld(ctx, Path.of(userWorld), System.getProperty("imm_ptl.featureTest.userWorldPos", "minecraft:overworld 0 100 0 0 0"));
            writeReport();
            if (!failures.isEmpty()) {
                throw new AssertionError(failures.size() + " feature checks failed:\n" + String.join("\n", failures));
            }
            return;
        }
        // gradle -Pimm_ptl.featureTest.only=dimstack: only the dimension stack scene
        if ("dimstack".equals(System.getProperty("imm_ptl.featureTest.only"))) {
            dimStackSection(ctx);
            writeReport();
            if (!failures.isEmpty()) {
                throw new AssertionError(failures.size() + " feature checks failed:\n" + String.join("\n", failures));
            }
            return;
        }

        section("first start screen", () -> initialScreen(ctx));
        section("config presets", () -> configPresets(ctx));
        if (FabricLoader.getInstance().isModLoaded("sodium")) {
            section("Sodium video settings page", () -> sodiumOptions(ctx));
        }

        try (TestSingleplayerContext sp = ctx.worldBuilder()
            .adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
            .create()
        ) {
            TestServerContext srv = sp.getServer();
            sp.getConnection().waitForChunksRender();
            srv.runCommand("time set noon");
            srv.runCommand("gamerule doDaylightCycle false");
            setFlying(srv, true);

            // gradle -Pimm_ptl.featureTest.only=hidden|terrain: only that scene
            String only = System.getProperty("imm_ptl.featureTest.only");
            if ("hidden".equals(only) || "terrain".equals(only)) {
                if ("hidden".equals(only)) {
                    section("hidden portal culling", () -> hiddenPortalCulling(ctx, srv));
                }
                else {
                    section("terrain behind a far portal", () -> farPortalTerrain(ctx, srv));
                }
                writeReport();
                if (!failures.isEmpty()) {
                    throw new AssertionError(failures.size() + " feature checks failed:\n" + String.join("\n", failures));
                }
                return;
            }

            section("singleplayer nether portal", () -> netherPortal(ctx, srv, "sp"));
            section("end portal", () -> endPortal(ctx, srv));
            section("make_portal command", () -> makePortalCommand(ctx, srv));
            section("world wrapping", () -> worldWrapping(ctx, srv));
            section("dimension stack", () -> dimensionStack(ctx, srv));
            section("rotating portal", () -> rotatingPortal(ctx, srv));
            section("hidden portal culling", () -> hiddenPortalCulling(ctx, srv));
            section("terrain behind a far portal", () -> farPortalTerrain(ctx, srv));
            section("command stick", () -> commandStick(ctx, srv));
            section("strip clipping (leashes)", this::stripClipping);
            section("breakable mirror", () -> breakableMirror(ctx, srv));
            section("portal helper", () -> portalHelper(ctx, srv));
            section("portal wand", () -> portalWand(ctx, srv));
            section("portal commands", () -> portalCommands(ctx, srv));

            // global portals for the migration check below
            srv.runCommand("portal global create_inward_wrapping -40 -40 40 40");
            ctx.waitTicks(20);
            migrationPortalCount = srv.computeOnServer(s -> GlobalPortalStorage.getGlobalPortals(s.overworld()).size());
            worldSave = sp.getWorldSave();
        }
        // loaded when the world is opened again (dynamic registries are only loaded with the world)
        writeTestDatapack();

        section("migration of old global portal files", () -> migration(ctx));

        dimStackSection(ctx);

        // Multiplayer: a local dedicated server in the test's run directory (build/run/clientGameTest).
        // It needs eula=true in its eula.txt (the Minecraft EULA; the project owner accepted it for this
        // test server). Disable with gradle -Pimm_ptl.featureTest.dedicated=false.
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

    private void dimStackSection(ClientGameTestContext ctx) {
        section("dimension stack when creating a world (UI)", () -> {
            try {
                dimStackWorldCreation(ctx);
            }
            finally {
                // back to the title screen for the next section, also after a failure
                ctx.runOnClient(mc -> {
                    if (mc.level != null) {
                        mc.disconnectFromWorld(net.minecraft.network.chat.Component.empty());
                    }
                    else if (!(mc.gui.screen() instanceof TitleScreen)) {
                        mc.gui.setScreen(new TitleScreen());
                    }
                });
                ctx.waitForScreen(TitleScreen.class);
                // the integrated server stops in the background; the test must not end while it runs
                ctx.waitFor(mc -> mc.getSingleplayerServer() == null
                    && !net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl.isServerRunning, 1200);
                ctx.runOnClient(mc -> {
                    if (!(mc.gui.screen() instanceof TitleScreen)) {
                        mc.gui.setScreen(new TitleScreen());
                    }
                });
                ctx.waitForScreen(TitleScreen.class);
            }
        });
    }

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
     * Like in a stronghold: a ring of end portal frames; the player inserts the last eye of ender
     * (the real item use, which fills the hole with placeholder blocks and creates the see-through
     * end portal); falling into it leads to the End.
     */
    private void endPortal(ClientGameTestContext ctx, TestServerContext srv) {
        int ground = groundY(srv, Level.OVERWORLD, 60, 20);
        int cx = 60, cz = 20, y = ground;
        Vec3 center = new Vec3(cx + 0.5, y + 0.5, cz + 0.5);
        for (int d = -1; d <= 1; d++) {
            srv.runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:end_portal_frame[facing=south,eye=true]", cx + d, y, cz - 2));
            srv.runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:end_portal_frame[facing=north,eye=true]", cx + d, y, cz + 2));
            srv.runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:end_portal_frame[facing=east,eye=true]", cx - 2, y, cz + d));
            srv.runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:end_portal_frame[facing=west,eye=%b]", cx + 2, y, cz + d, d != 1));
        }
        srv.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", cx - 1, y, cz - 1, cx + 1, y, cz + 1));

        // the player inserts the last eye
        BlockPos lastFrame = new BlockPos(cx + 2, y, cz + 1);
        srv.runOnServer(s -> {
            ServerPlayer player = s.getPlayerList().getPlayers().get(0);
            ItemStack eye = new ItemStack(Items.ENDER_EYE);
            player.setItemInHand(InteractionHand.MAIN_HAND, eye);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(lastFrame).add(0, 0.5, 0), Direction.UP, lastFrame, false);
            eye.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        });
        ctx.waitTicks(10);
        check("end: inserting the last eye filled the frame", srv.computeOnServer(s ->
            s.overworld().getBlockState(lastFrame).getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE)), "");
        boolean created = srv.computeOnServer(s ->
            !s.overworld().getEntitiesOfClass(EndPortalEntity.class, new AABB(center, center).inflate(4)).isEmpty()
        );
        check("end: completing the frame created an end portal entity", created, "");
        if (!created) {
            return;
        }

        srv.runCommand(String.format(Locale.ROOT, "tp Player0 %.1f %d %.1f 180 60", cx + 0.5, y + 6, cz + 3.5));
        ctx.waitTicks(40);
        screenshot(ctx, "end_portal_from_above");

        srv.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> {
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
        }));
        srv.runCommand(String.format(Locale.ROOT, "tp Player0 %.1f %d %.1f 0 90", cx + 0.5, y + 3, cz + 0.5));
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = false;
            mc.player.onUpdateAbilities();
        });
        boolean inEnd = waitFor(ctx, 400, () -> ctx.computeOnClient(mc -> mc.level != null && mc.level.dimension() == Level.END));
        check("end: falling into the end portal enters the End", inEnd,
            "client dim " + ctx.computeOnClient(mc -> mc.level.dimension().identifier()));
        if (inEnd) {
            ctx.waitTicks(60);
            screenshot(ctx, "arrived_in_end");
            endExitPortal(ctx, srv);
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
     * In the End: kill the dragon, wait for the exit portal on the podium, and fall into it.
     * That leaves the End (vanilla shows the credits first, then the player is in the overworld).
     */
    private void endExitPortal(ClientGameTestContext ctx, TestServerContext srv) {
        boolean dragon = waitFor(ctx, 600, () -> srv.computeOnServer(s -> !s.getLevel(Level.END).getDragons().isEmpty()));
        check("end: the ender dragon spawned", dragon, "");
        if (!dragon) {
            return;
        }
        srv.runCommand("execute in minecraft:the_end run kill @e[type=minecraft:ender_dragon]");

        // the death animation takes 200 ticks, then the exit portal is placed on the podium
        BlockPos[] portalBlock = {null};
        boolean exit = waitFor(ctx, 1200, () -> {
            portalBlock[0] = srv.computeOnServer(s -> findBlock(s.getLevel(Level.END), 8, 40, 90, Blocks.END_PORTAL));
            return portalBlock[0] != null;
        });
        check("end: killing the dragon created the exit portal", exit, "");
        if (!exit) {
            return;
        }
        BlockPos p = portalBlock[0];

        srv.runCommand(String.format(Locale.ROOT, "execute in minecraft:the_end run tp Player0 %d %d %d 0 50", p.getX(), p.getY() + 4, p.getZ() - 6));
        ctx.waitTicks(40);
        screenshot(ctx, "end_exit_portal");

        // fall into it
        srv.runCommand(String.format(Locale.ROOT, "execute in minecraft:the_end run tp Player0 %.1f %d %.1f 0 90", p.getX() + 0.5, p.getY() + 3, p.getZ() + 0.5));
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = false;
            mc.player.onUpdateAbilities();
        });
        setFlying(srv, false);
        boolean left = waitFor(ctx, 600, () -> ctx.computeOnClient(mc ->
            mc.gui.screen() instanceof WinScreen
                || (mc.level != null && mc.level.dimension() == Level.OVERWORLD)
        ));
        check("end: entering the exit portal leaves the End (credits or overworld)", left,
            "client dim " + ctx.computeOnClient(mc -> mc.level == null ? "none" : mc.level.dimension().identifier().toString())
                + ", screen " + ctx.computeOnClient(mc -> String.valueOf(mc.gui.screen())));
        // skip the credits
        ctx.runOnClient(mc -> {
            if (mc.gui.screen() instanceof WinScreen winScreen) {
                winScreen.onClose();
            }
        });
        boolean inOverworld = waitFor(ctx, 600, () -> ctx.computeOnClient(mc ->
            mc.level != null && mc.level.dimension() == Level.OVERWORLD && mc.gui.screen() == null));
        check("end: after the credits the player is in the overworld", inOverworld,
            "client dim " + ctx.computeOnClient(mc -> mc.level == null ? "none" : mc.level.dimension().identifier().toString()));
        if (inOverworld) {
            ctx.waitTicks(40);
            screenshot(ctx, "back_from_end");
        }
    }

    private static @org.jetbrains.annotations.Nullable BlockPos findBlock(
        ServerLevel level, int radius, int minY, int maxY, net.minecraft.world.level.block.Block block
    ) {
        for (BlockPos pos : BlockPos.betweenClosed(-radius, minY, -radius, radius, maxY, radius)) {
            if (level.getBlockState(pos).is(block)) {
                return pos.immutable();
            }
        }
        return null;
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
            boolean rendered = waitFor(ctx, 1200, () -> ctx.computeOnClient(mc -> mc.levelRenderer.hasRenderedAllSections()));
            check("migration: the reopened world rendered", rendered, ctx.computeOnClient(mc -> String.format(Locale.ROOT,
                "renderer is the overworld helper's %b, camera %s",
                ClientWorldLoader.RENDER_HELPER_MAP.get(Level.OVERWORLD) != null
                    && ClientWorldLoader.RENDER_HELPER_MAP.get(Level.OVERWORLD).levelRenderer == mc.levelRenderer,
                mc.gameRenderer.mainCamera().position())));
            boolean chunksLoaded = waitFor(ctx, 600, () -> missingChunksAroundPlayer(ctx).isEmpty());
            check("migration: all chunks around the player are loaded", chunksLoaded,
                "missing " + missingChunksAroundPlayer(ctx));
            TestServerContext srv = sp.getServer();
            int count = srv.computeOnServer(s -> GlobalPortalStorage.getGlobalPortals(s.overworld()).size());
            check("migration: the global portals were loaded from the old file", count == migrationPortalCount,
                "count " + count + ", expected " + migrationPortalCount);
            boolean synced = waitFor(ctx, 200, () -> ctx.computeOnClient(mc ->
                GlobalPortalStorage.getGlobalPortals(mc.level).size() == count));
            check("migration: the migrated portals are synced to the client", synced, "");

            section("custom portal generation (datapack)", () -> customPortalGeneration(ctx, srv));
        }
    }

    // ---- helpers ----

    /**
     * A portal that rolls the world by 90 degrees around the walking direction (its normal), which pitch and yaw
     * can't express. Walking through it, the view must stay continuous (right after the teleport the camera is
     * still rolled, TransformationManager's animation delta) and then turn upright within the animation (1 s).
     */
    private void rotatingPortal(ClientGameTestContext ctx, TestServerContext srv) {
        int y = groundY(srv, Level.OVERWORLD, 300, 300);
        srv.runOnServer(s -> {
            Portal p = Portal.ENTITY_TYPE.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            p.setOriginPos(new Vec3(300.5, y + 1.5, 300.5));
            p.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 2, 3);
            p.setDestinationDimension(Level.OVERWORLD);
            p.setDestination(new Vec3(340.5, y + 1.5, 300.5));
            p.setRotation(qouteall.q_misc_util.my_util.DQuaternion.rotationByDegrees(new Vec3(0, 0, 1), 90));
            qouteall.imm_ptl.core.McHelper.spawnServerEntity(p);
        });
        srv.runCommand(String.format(Locale.ROOT, "tp Player0 300.5 %d 302.0 180 0", y));
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        });

        double upBefore = cameraUpY(ctx);
        boolean teleported = false;
        double upAfter = Double.NaN;
        boolean animating = false;
        for (int i = 0; i < 40 && !teleported; i++) {
            ctx.runOnClient(mc -> mc.player.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ() - 0.1));
            ctx.waitTick();
            teleported = ctx.computeOnClient(mc -> mc.player.getX() > 320);
            if (teleported) {
                upAfter = cameraUpY(ctx);
                animating = ctx.computeOnClient(mc -> qouteall.imm_ptl.core.render.TransformationManager.isAnimationRunning());
            }
        }
        check("rotating portal: walking through it teleports", teleported,
            ctx.computeOnClient(mc -> mc.player.position().toString()));
        if (!teleported) {
            return;
        }
        screenshot(ctx, "rotating_portal_just_after");
        check("rotating portal: the view is continuous (camera still rolled right after the teleport)",
            upBefore > 0.99 && Math.abs(upAfter) < 0.5 && animating,
            String.format(Locale.ROOT, "camera up.y before %.3f after %.3f, animation running %b", upBefore, upAfter, animating));
        ctx.waitTicks(40);
        double upLater = cameraUpY(ctx);
        check("rotating portal: the camera turns upright after the animation", upLater > 0.99,
            String.format(Locale.ROOT, "camera up.y %.3f", upLater));
        screenshot(ctx, "rotating_portal_later");
    }

    /**
     * The settings in Sodium's video settings screen (IPSodiumConfigEntryPoint, Sodium's config API): Sodium registered
     * them, choosing a preset there and applying sets its settings (like the config screen), and the page opens.
     * Sodium's classes are only reached by reflection (it's not on the test compile classpath).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void sodiumOptions(ClientGameTestContext ctx) {
        String result = ctx.computeOnClient(mc -> {
            try {
                Object config = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager").getField("CONFIG").get(null);
                java.lang.reflect.Field modOptionsField = config.getClass().getDeclaredField("modOptions");
                modOptionsField.setAccessible(true);
                Object ours = null;
                for (Object modOptions : (List<?>) modOptionsField.get(config)) {
                    if (modOptions.getClass().getMethod("configId").invoke(modOptions).equals("immersive_portals")) {
                        ours = modOptions;
                    }
                }
                if (ours == null) {
                    return "not registered";
                }
                java.lang.reflect.Field optionsField = config.getClass().getDeclaredField("options");
                optionsField.setAccessible(true);
                Map<net.minecraft.resources.Identifier, Object> options = (Map) optionsField.get(config);
                Object preset = options.get(net.minecraft.resources.Identifier.fromNamespaceAndPath("immersive_portals", "preset"));
                Object layer = options.get(net.minecraft.resources.Identifier.fromNamespaceAndPath("immersive_portals", "maxportallayer"));
                java.lang.reflect.Method modify = preset.getClass().getMethod("modifyValue", Object.class);
                java.lang.reflect.Method apply = config.getClass().getMethod("applyAllOptions");
                java.lang.reflect.Method reset = config.getClass().getMethod("resetAllOptionsFromBindings");
                java.lang.reflect.Method applied = layer.getClass().getMethod("getAppliedValue");

                modify.invoke(preset, qouteall.imm_ptl.core.platform_specific.ConfigPreset.performance);
                apply.invoke(config);
                reset.invoke(config);
                IPConfig c = IPConfig.getConfig();
                String afterPerformance = c.preset + " layer " + c.maxPortalLayer + " running " + IPGlobal.maxPortalLayer
                    + " shown " + applied.invoke(layer);

                modify.invoke(preset, qouteall.imm_ptl.core.platform_specific.ConfigPreset.balanced);
                apply.invoke(config);
                reset.invoke(config);
                String afterBalanced = c.preset + " layer " + c.maxPortalLayer + " shown " + applied.invoke(layer);
                return afterPerformance + "; " + afterBalanced;
            }
            catch (ReflectiveOperationException e) {
                return e.toString();
            }
        });
        check("Sodium video settings: choosing a preset there and applying sets its settings",
            result.equals("performance layer 2 running 2 shown 2; balanced layer 5 shown 5"), result);

        // our page, opened directly
        ctx.runOnClient(mc -> {
            try {
                Object config = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager").getField("CONFIG").get(null);
                java.lang.reflect.Field modOptionsField = config.getClass().getDeclaredField("modOptions");
                modOptionsField.setAccessible(true);
                for (Object modOptions : (List<?>) modOptionsField.get(config)) {
                    if (modOptions.getClass().getMethod("configId").invoke(modOptions).equals("immersive_portals")) {
                        Object page = ((List<?>) modOptions.getClass().getMethod("pages").invoke(modOptions)).get(0);
                        Class<?> screenClass = Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen");
                        Class<?> pageClass = Class.forName("net.caffeinemc.mods.sodium.client.config.structure.OptionPage");
                        mc.gui.setScreen((net.minecraft.client.gui.screens.Screen)
                            screenClass.getMethod("createScreen", net.minecraft.client.gui.screens.Screen.class, pageClass)
                                .invoke(null, new TitleScreen(), page));
                    }
                }
            }
            catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(10);
        String screen = ctx.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
        screenshot(ctx, "sodium_options");
        check("Sodium video settings: our page opens", screen.equals("VideoSettingsScreen"), screen);
        ctx.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
        ctx.waitForScreen(TitleScreen.class);
    }

    /**
     * Config presets (ConfigPreset): choosing one sets its settings when the config is saved (also the running values
     * in IPGlobal), changing one of them by hand makes the preset "custom". And the config screen opens and saves.
     */
    private void configPresets(ClientGameTestContext ctx) {
        String quality = ctx.computeOnClient(mc -> {
            IPConfig c = IPConfig.getConfig();
            c.preset = qouteall.imm_ptl.core.platform_specific.ConfigPreset.quality;
            c.saveConfigFile();
            return c.preset + " far updates " + c.reduceFarPortalUpdates + " running " + IPGlobal.reduceFarPortalUpdates;
        });
        check("config presets: quality updates far portals every frame",
            quality.equals("quality far updates false running false"), quality);

        String performance = ctx.computeOnClient(mc -> {
            IPConfig c = IPConfig.getConfig();
            c.preset = qouteall.imm_ptl.core.platform_specific.ConfigPreset.performance;
            c.saveConfigFile();
            return c.preset + " layer " + c.maxPortalLayer + " reduced " + c.reducedPortalRendering + " cap " + c.indirectLoadingRadiusCap
                + " yourself " + c.renderYourselfInPortal + " far updates " + c.reduceFarPortalUpdates + " running layer " + IPGlobal.maxPortalLayer;
        });
        check("config presets: choosing performance sets its settings",
            performance.equals("performance layer 2 reduced true cap 4 yourself false far updates true running layer 2"), performance);

        String custom = ctx.computeOnClient(mc -> {
            IPConfig c = IPConfig.getConfig();
            c.maxPortalLayer = 3;
            c.saveConfigFile();
            return c.preset + " layer " + c.maxPortalLayer;
        });
        check("config presets: changing one of its settings by hand makes it custom", custom.equals("custom layer 3"), custom);

        String balanced = ctx.computeOnClient(mc -> {
            IPConfig c = IPConfig.getConfig();
            c.preset = qouteall.imm_ptl.core.platform_specific.ConfigPreset.balanced;
            c.saveConfigFile();
            return c.preset + " layer " + c.maxPortalLayer + " reduced " + c.reducedPortalRendering + " cap " + c.indirectLoadingRadiusCap
                + " yourself " + c.renderYourselfInPortal + " adjustment " + c.enableClientPerformanceAdjustment;
        });
        check("config presets: choosing balanced restores the defaults",
            balanced.equals("balanced layer 5 reduced false cap 8 yourself true adjustment true"), balanced);

        // the config screen (with the preset at the top of the client settings) opens and saves
        ctx.runOnClient(mc -> mc.gui.setScreen(
            qouteall.imm_ptl.core.platform_specific.IPConfigGUI.createClothConfigScreen(new TitleScreen())
        ));
        ctx.waitTicks(5);
        screenshot(ctx, "config_screen");
        ctx.clickScreenButton("text.cloth-config.save_and_done");
        ctx.waitForScreen(TitleScreen.class);
        String afterScreen = ctx.computeOnClient(mc -> IPConfig.getConfig().preset.name());
        check("config presets: saving the config screen keeps the preset", afterScreen.equals("balanced"), afterScreen);
    }

    /**
     * The screen shown at the first start (and again when its content changes): all pages, then "I know" on the last
     * one stores the content version, so that it isn't shown again.
     */
    private void initialScreen(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            IPConfig.getConfig().initialScreenVersion = 0;
            mc.gui.setScreen(new IPortalInitialScreen(() -> mc.gui.setScreen(new TitleScreen())));
        });
        List<String> overflowing = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ctx.waitTicks(2);
            screenshot(ctx, "initial_screen_" + i);
            // the page's text is inside the screen and above the buttons (at this window size)
            int page = i;
            ctx.runOnClient(mc -> {
                var screen = mc.gui.screen();
                var text = screen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.MultiLineTextWidget)
                    .map(c -> (net.minecraft.client.gui.components.AbstractWidget) c).findFirst().orElseThrow();
                int buttonsTop = screen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.Button)
                    .mapToInt(c -> ((net.minecraft.client.gui.components.AbstractWidget) c).getY()).min().orElseThrow();
                if (text.getX() < 0 || text.getRight() > screen.width || text.getBottom() > buttonsTop) {
                    overflowing.add(String.format(Locale.ROOT, "page %d: text x %d..%d y ..%d, screen width %d, buttons at y %d",
                        page + 1, text.getX(), text.getRight(), text.getBottom(), screen.width, buttonsTop));
                }
            });
            ctx.clickScreenButton("iportal.initial_screen.i_know");
        }
        check("first start screen: every page fits the screen (1280x720)", overflowing.isEmpty(), String.join("; ", overflowing));
        ctx.waitForScreen(TitleScreen.class);
        int version = ctx.computeOnClient(mc -> IPConfig.getConfig().initialScreenVersion);
        check("first start screen: going through it stores its content version (it is not shown again)",
            version == IPortalInitialScreen.CONTENT_VERSION && !IPortalInitialScreen.shouldShow(IPConfig.getConfig()),
            "version " + version);
    }

    /**
     * Portals hidden behind blocks are not rendered (PortalOcclusionCulling).
     * The camera is in a small air pocket in a solid 3x3x3 block of sections (so the cave culling graph can't
     * leave the camera's section and its neighbors); the portal is in a pocket two sections away, facing the camera.
     * Then a tunnel is dug between the pockets, and the portal must be rendered again.
     */
    private void hiddenPortalCulling(ClientGameTestContext ctx, TestServerContext srv) {
        srv.runCommand("tp Player0 487.5 100 487.5 -90 0");
        ctx.waitTicks(60);
        // sections x/z 29..31, y -3..-1: blocks 464..511, -48..-1 (fill is limited to 32768 blocks)
        for (int y = -48; y < 0; y += 12) {
            srv.runCommand(String.format(Locale.ROOT, "fill 464 %d 464 511 %d 511 minecraft:stone", y, y + 11));
        }
        // a floor in the portal's section: Sodium counts sections without blocks as visible
        srv.runCommand("fill 512 -31 480 527 -31 495 minecraft:stone");
        // the camera's pocket (section 30 -2 30) and the portal's pocket (section 32 -2 30)
        srv.runCommand("fill 486 -26 486 489 -23 489 minecraft:air");
        srv.runCommand("fill 516 -30 482 525 -19 493 minecraft:air");
        srv.runOnServer(s -> {
            Portal p = Portal.ENTITY_TYPE.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            p.setOriginPos(new Vec3(520.5, -24.5, 487.5));
            // facing -x, towards the camera
            p.setOrientationAndSize(new Vec3(0, 0, 1), new Vec3(0, 1, 0), 3, 4);
            p.setDestinationDimension(Level.OVERWORLD);
            p.setDestination(new Vec3(520.5, 150, 600.5));
            qouteall.imm_ptl.core.McHelper.spawnServerEntity(p);
        });
        srv.runCommand("tp Player0 487.5 -26 487.5 -90 0");
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        });
        ctx.waitTicks(80);

        // The portal (to a far place: an extra view renderer of the overworld) exists while the stone's sections change from empty to
        // filled: the main renderer must still get those changes (they are taken once from the ClientLevel).
        // Sodium replaces the vanilla section lists: nothing to check here
        boolean sodium = ctx.computeOnClient(mc ->
            qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface.invoker.isSodiumPresent());
        boolean stoneRendered = sodium || ctx.computeOnClient(mc -> {
            long cameraSection = net.minecraft.core.SectionPos.asLong(net.minecraft.core.BlockPos.containing(mc.gameRenderer.mainCamera().position()));
            return mc.levelRenderer.visibleSections().stream().anyMatch(section -> section.getSectionNode() == cameraSection);
        });
        check("hidden portal: the filled sections around the camera are rendered (extra view renderer present)", stoneRendered,
            hiddenPortalDiagnostics(ctx));
        // the graph was built before the stone's meshes were compiled; vanilla rebuilds it when the camera moves.
        // Rebuild it once all sections are built.
        waitFor(ctx, 600, () -> ctx.computeOnClient(mc -> mc.levelRenderer.hasRenderedAllSections()));
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> mc.levelRenderer.sectionOcclusionGraph().invalidate());
        ctx.waitTicks(20);

        ctx.runOnClient(mc -> qouteall.imm_ptl.core.IPCGlobal.cullHiddenPortals = false);
        long[] off = countViews(ctx, 20);
        ctx.runOnClient(mc -> qouteall.imm_ptl.core.IPCGlobal.cullHiddenPortals = true);
        ctx.waitTicks(20);
        long[] sealed = countViews(ctx, 20);
        check("hidden portal: rendered when culling is off (the scene works)", off[0] > 0,
            String.format(Locale.ROOT, "views %d", off[0]));
        check("hidden portal: not rendered behind blocks" + (sodium ? " (Sodium)" : ""),
            sealed[0] == 0 && sealed[1] > 0,
            String.format(Locale.ROOT, "views %d, hidden %d; %s", sealed[0], sealed[1], hiddenPortalDiagnostics(ctx)));
        screenshot(ctx, "hidden_portal_sealed");

        srv.runCommand("fill 486 -26 486 522 -23 489 minecraft:air");
        ctx.waitTicks(80);
        long[] open = countViews(ctx, 20);
        check("hidden portal: rendered again when the tunnel is dug", open[0] > 0,
            String.format(Locale.ROOT, "views %d, hidden %d; %s", open[0], open[1], hiddenPortalDiagnostics(ctx)));
        screenshot(ctx, "hidden_portal_open");

        // Cropped views (PortalViewCrop): the portal, 3x4 blocks and 33 blocks away, covers a small part of the
        // screen. Its view is rendered only for that rectangle, and the image is the same as with a full-size view.
        double croppedShare = viewPixelShare(ctx, 20);
        int[] cropped = captureFrame(ctx);
        ctx.runOnClient(mc -> qouteall.imm_ptl.core.IPCGlobal.cropPortalViews = false);
        ctx.waitTicks(10);
        double fullShare = viewPixelShare(ctx, 20);
        int[] full = captureFrame(ctx);
        ctx.runOnClient(mc -> qouteall.imm_ptl.core.IPCGlobal.cropPortalViews = true);
        boolean shaderpack = ctx.computeOnClient(mc ->
            qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface.invoker.isShaderpackInUse());
        check("cropped portal view: a small portal's view is rendered for a small part of the window" + (shaderpack ? " (not with a shaderpack)" : ""),
            shaderpack ? croppedShare > 0.99 : (croppedShare > 0 && croppedShare < 0.2 && fullShare > 0.99),
            String.format(Locale.ROOT, "view pixels / window pixels: cropped %.3f, full-size %.3f", croppedShare, fullShare));
        int differing = countDifferingPixels(cropped, full);
        check("cropped portal view: the image is the same as with a full-size view",
            cropped.length == full.length && differing < cropped.length / 500,
            String.format(Locale.ROOT, "%d of %d pixels differ", differing, cropped.length));

        // Fewer updates for far portals (FarPortalViewReuse): the camera stands still, the portal is far and small,
        // so its view is rendered only every 2nd frame and its last image is shown in between. Not when it's off.
        if (!shaderpack) {
            long[] reduced = countRenderedAndReused(ctx, 20);
            ctx.runOnClient(mc -> qouteall.imm_ptl.core.IPGlobal.reduceFarPortalUpdates = false);
            long[] everyFrame = countRenderedAndReused(ctx, 20);
            ctx.runOnClient(mc -> qouteall.imm_ptl.core.IPGlobal.reduceFarPortalUpdates = true);
            check("far portal updates: a far, small portal's view is rendered every 2nd frame while the camera stands still",
                reduced[1] > 0 && Math.abs(reduced[0] - reduced[1]) <= Math.max(2, reduced[0] / 5),
                String.format(Locale.ROOT, "rendered %d, reused %d", reduced[0], reduced[1]));
            check("far portal updates: rendered every frame when turned off",
                everyFrame[0] > 0 && everyFrame[1] == 0,
                String.format(Locale.ROOT, "rendered %d, reused %d", everyFrame[0], everyFrame[1]));
            // the camera turns a little each frame: the old image would be misaligned, so it's rendered each frame
            long[] turning = countRenderedAndReusedWhileTurning(ctx, 20);
            check("far portal updates: rendered every frame while the camera turns",
                turning[0] > 0 && turning[1] == 0,
                String.format(Locale.ROOT, "rendered %d, reused %d", turning[0], turning[1]));
        }

        // "Improved Transparency" (order-independent transparency): translucent terrain in view (water, stained
        // glass) is drawn with OIT pipelines derived from the terrain pipelines, which include more uniform blocks
        // (Sodium's terrain also gets minecraft:projection.glsl). Their compile failed with the clip plane in both
        // blocks, and the game crashed ("Failed to find or load pipeline ... oit_depth_bounds_sodium_terrain").
        srv.runCommand("fill 492 -26 486 500 -26 489 minecraft:water");
        srv.runCommand("fill 504 -25 486 504 -23 489 minecraft:light_blue_stained_glass");
        ctx.runOnClient(mc -> mc.options.improvedTransparency().set(true));
        ctx.waitTicks(60);
        long[] oit = countViews(ctx, 20);
        screenshot(ctx, "improved_transparency");
        ctx.runOnClient(mc -> mc.options.improvedTransparency().set(false));
        ctx.waitTicks(20);
        check("improved transparency: translucent terrain and the portal view render (no crash)", oit[0] > 0,
            String.format(Locale.ROOT, "views %d", oit[0]));
        srv.runCommand("fill 486 -26 486 522 -23 489 minecraft:air");

        srv.runOnServer(s -> {
            for (Portal p : s.overworld().getEntitiesOfClass(Portal.class, new AABB(510, -40, 470, 530, -10, 500))) {
                p.discard();
            }
        });
    }

    /**
     * The terrain seen through a nether portal whose other side is far from anywhere the player has been
     * (generated and loaded fresh), like the report of terrain missing in a portal view that never filled in.
     * ViewDiagnostics tells why terrain would be missing: chunks not loaded, sections not built, or neither.
     * Viewed from 3 blocks away, from inside the frame, and with a second far portal in view
     * (two views of the overworld far apart share one renderer grid).
     */
    private void farPortalTerrain(ClientGameTestContext ctx, TestServerContext srv) {
        String inNether = "execute in minecraft:the_nether run ";
        // a render distance above indirectLoadingRadiusCap (8), and no FPS based loading reduction (not tested here)
        int oldRenderDistance = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(12);
            // the server loads by the player's requested view distance, sent with the client information
            mc.options.broadcastOptions();
            IPGlobal.enableClientPerformanceAdjustment = false;
        });
        int x0 = 999, y = 66, z = 1000;
        srv.runCommand(inNether + "tp Player0 1001.0 90 1010.0 180 0");
        ctx.waitTicks(60);
        srv.runCommand(inNether + String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
            x0 - 5, y - 1, z - 6, x0 + 8, y + 10, z + 10));
        srv.runCommand(inNether + String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone",
            x0 - 5, y - 1, z - 6, x0 + 8, y - 1, z + 10));
        srv.runCommand(inNether + String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:obsidian",
            x0, y, z, x0 + 3, y + 4, z));
        srv.runCommand(inNether + String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
            x0 + 1, y + 1, z, x0 + 2, y + 3, z));
        srv.runCommand(inNether + String.format(Locale.ROOT, "setblock %d %d %d minecraft:fire", x0 + 1, y + 1, z));

        AABB frameBox = new AABB(x0 - 1, y - 1, z - 2, x0 + 5, y + 6, z + 3);
        boolean generated = waitFor(ctx, 1200, () -> srv.computeOnServer(s ->
            s.getLevel(Level.NETHER).getEntitiesOfClass(Portal.class, frameBox).size() >= 2
        ));
        check("far portal terrain: lighting the frame in the nether created portals", generated, "");
        if (!generated) {
            return;
        }
        Vec3 dest = srv.computeOnServer(s -> s.getLevel(Level.NETHER).getEntitiesOfClass(Portal.class, frameBox).get(0).getDestPos());
        boolean destPortals = waitFor(ctx, 1200, () -> srv.computeOnServer(s ->
            !s.overworld().getEntitiesOfClass(Portal.class, new AABB(dest, dest).inflate(3)).isEmpty()
        ));
        check("far portal terrain: the overworld side has portals", destPortals, "dest " + dest);

        srv.runCommand(inNether + String.format(Locale.ROOT, "tp Player0 %.1f %d %.1f 180 0", x0 + 2.0, y, z + 3.5));
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
            qouteall.imm_ptl.core.render.ViewDiagnostics.enabled = true;
        });

        List<String> log = new ArrayList<>();
        log.add("destination " + dest);
        String front = sampleTerrain(ctx, "front", 6, 100, log);
        screenshot(ctx, "far_portal_terrain_front");
        // standing at the portal: its other side is loaded and rendered as far as the render distance
        String frontDistance = ctx.computeOnClient(mc -> qouteall.imm_ptl.core.render.ViewDiagnostics.getLastFrame().stream()
            .map(r -> String.valueOf(r.renderDistance())).reduce("", (a, b) -> a + b + " "));
        check("far portal terrain: the portal in front of the player is rendered as far as the render distance (12)",
            frontDistance.trim().equals("12"), "view render distances: " + frontDistance);

        // standing in the frame, just before the portal plane (z + 0.5)
        srv.runCommand(inNether + String.format(Locale.ROOT, "tp Player0 %.1f %d %.2f 180 0", x0 + 2.0, y + 1, z + 0.85));
        ctx.waitTicks(20);
        String inFrame = sampleTerrain(ctx, "in frame", 2, 100, log);
        screenshot(ctx, "far_portal_terrain_in_frame");

        // a second portal in view, to another far place of the overworld
        srv.runOnServer(s -> {
            Portal p = Portal.ENTITY_TYPE.create(s.getLevel(Level.NETHER), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            p.setOriginPos(new Vec3(x0 - 2.5, y + 2.5, z - 0.5));
            p.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 3, 4);
            p.setDestinationDimension(Level.OVERWORLD);
            p.setDestination(dest.add(3000, 0, 3000));
            qouteall.imm_ptl.core.McHelper.spawnServerEntity(p);
        });
        srv.runCommand(inNether + String.format(Locale.ROOT, "tp Player0 %.1f %d %.1f 180 0", x0 + 0.5, y, z + 6.5));
        ctx.waitTicks(20);
        String two = sampleTerrain(ctx, "two far portals", 4, 100, log);
        screenshot(ctx, "far_portal_terrain_two");

        ctx.runOnClient(mc -> qouteall.imm_ptl.core.render.ViewDiagnostics.enabled = false);
        // back to the overworld for the next sections (they run commands as the player)
        srv.runOnServer(s -> s.getLevel(Level.NETHER).getEntitiesOfClass(Portal.class, new AABB(x0 - 6, y - 2, z - 3, x0 - 1, y + 7, z + 2))
            .forEach(p -> p.discard()));
        // while pointing at a block through the portal: a vanilla dimension change renders a frame while the
        // player is still in the old level, and pointing through the portal there disconnected the client
        srv.runCommand(inNether + String.format(Locale.ROOT, "tp Player0 %.1f %d %.1f 180 10", x0 + 2.0, y, z + 2.5));
        ctx.waitTicks(20);
        boolean pointingThrough = ctx.computeOnClient(mc -> qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient.remotePointedDim != null);
        srv.runCommand("execute in minecraft:overworld run tp Player0 0.5 -58 0.5");
        ctx.waitTicks(40);
        String afterChange = ctx.computeOnClient(mc -> mc.getConnection() == null ? "disconnected"
            : String.valueOf(mc.level.dimension().identifier()));
        check("far portal terrain: a dimension change while pointing through a portal keeps the client connected",
            afterChange.equals("minecraft:overworld"), "pointing through the portal before: " + pointingThrough + ", after: " + afterChange);
        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(oldRenderDistance);
            mc.options.broadcastOptions();
            IPGlobal.enableClientPerformanceAdjustment = true;
        });
        ctx.waitTicks(40);
        try {
            Files.write(out.resolve("far_portal_terrain.txt"), log);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        check("far portal terrain: the view has terrain, all buildable sections built (front)", front == null, String.valueOf(front));
        check("far portal terrain: the view has terrain, all buildable sections built (in the frame)", inFrame == null, String.valueOf(inFrame));
        check("far portal terrain: both views have terrain, all buildable sections built (two far portals)", two == null, String.valueOf(two));
    }

    /**
     * Samples the portal view diagnostics; returns null if in the last sample every view has terrain and no unbuilt
     * sections, otherwise that sample.
     */
    private static @org.jetbrains.annotations.Nullable String sampleTerrain(
        ClientGameTestContext ctx, String name, int samples, int interval, List<String> log
    ) {
        String last = null;
        boolean bad = false;
        for (int i = 0; i < samples; i++) {
            ctx.waitTicks(interval);
            last = ctx.computeOnClient(mc -> {
                StringBuilder sb = new StringBuilder("grid moves " + qouteall.imm_ptl.core.render.ViewDiagnostics.getLastFrameGridMoves());
                for (var r : qouteall.imm_ptl.core.render.ViewDiagnostics.getLastFrame()) {
                    sb.append(" | ").append(r);
                }
                return sb.toString();
            });
            bad = ctx.computeOnClient(mc -> qouteall.imm_ptl.core.render.ViewDiagnostics.getLastFrame().stream()
                .anyMatch(r -> r.hasProblem()));
            log.add(name + " +" + (i + 1) * interval + " ticks: " + last);
        }
        return bad ? last : null;
    }

    private static String hiddenPortalDiagnostics(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Vec3 camera = mc.gameRenderer.mainCamera().position();
            List<Portal> portals = mc.level.getEntitiesOfClass(Portal.class, new AABB(510, -40, 470, 530, -10, 500));
            String why = portals.isEmpty() ? "no portal" :
                String.valueOf(qouteall.imm_ptl.core.render.PortalOcclusionCulling.whyVisible(portals.get(0), camera));
            // the graph's state of the sections from the camera to the portal (x 29..32, y -2, z 30)
            StringBuilder graphState = new StringBuilder();
            var viewArea = mc.levelRenderer.viewArea();
            var graph = mc.levelRenderer.sectionOcclusionGraph();
            for (int sx = 29; sx <= 32 && viewArea != null; sx++) {
                var section = viewArea.getRenderSectionAt(new net.minecraft.core.BlockPos(sx * 16, -32, 30 * 16));
                graphState.append(" x").append(sx).append(section == null ? ":none" :
                    (graph.getNode(section) != null ? ":node" : ":-") + "/" + (section.getSectionMesh() == net.minecraft.client.renderer.chunk.CompiledSectionMesh.EMPTY ? "empty"
                        : section.getSectionMesh() == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED ? "uncompiled" : "mesh")
                        + (mc.level.getChunk(sx, 30).getSection(mc.level.getSectionIndexFromSectionY(-2)).hasOnlyAir() ? "/air" : "/blocks"));
            }
            return String.format(Locale.ROOT, "camera %s, block %s, smartCull %b, visible sections %d, why visible: %s, graph%s",
                camera, mc.level.getBlockState(net.minecraft.core.BlockPos.containing(camera.add(1, 0, 0))),
                mc.smartCull, mc.levelRenderer.visibleSections().size(), why, graphState);
        });
    }

    /**
     * The pixels of the portal view targets per view rendered during the given ticks, relative to the window's.
     */
    private static double viewPixelShare(ClientGameTestContext ctx, int ticks) {
        long[] before = ctx.computeOnClient(mc -> new long[]{
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.views,
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.viewPixels
        });
        ctx.waitTicks(ticks);
        long[] after = ctx.computeOnClient(mc -> new long[]{
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.views,
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.viewPixels
        });
        long windowPixels = ctx.computeOnClient(mc -> (long) mc.getWindow().getWidth() * mc.getWindow().getHeight());
        long views = after[0] - before[0];
        return views == 0 ? 0 : (double) (after[1] - before[1]) / views / windowPixels;
    }

    // portal views rendered and portal views whose last image was shown again (FarPortalViewReuse) during the ticks
    private static long[] countRenderedAndReused(ClientGameTestContext ctx, int ticks) {
        long[] before = ctx.computeOnClient(mc -> new long[]{
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.views,
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.reusedViews
        });
        ctx.waitTicks(ticks);
        long[] after = ctx.computeOnClient(mc -> new long[]{
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.views,
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.reusedViews
        });
        return new long[]{after[0] - before[0], after[1] - before[1]};
    }

    // while set, the camera turns a little before every frame (a test tick renders its frames at the same partial tick)
    private static volatile boolean turnEachFrame = false;
    private static boolean turnHookRegistered = false;

    private static long[] countRenderedAndReusedWhileTurning(ClientGameTestContext ctx, int ticks) {
        if (!turnHookRegistered) {
            turnHookRegistered = true;
            int[] frame = {0};
            ctx.runOnClient(mc -> IPGlobal.PRE_GAME_RENDER_EVENT.register(() -> {
                if (turnEachFrame && mc.player != null) {
                    float y = mc.player.getYRot() + (frame[0]++ % 2 == 0 ? 0.2F : -0.2F);
                    mc.player.setYRot(y);
                    mc.player.yRotO = y;
                }
            }));
        }
        long[] before = ctx.computeOnClient(mc -> new long[]{
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.views,
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.reusedViews
        });
        float yaw = ctx.computeOnClient(mc -> mc.player.getYRot());
        turnEachFrame = true;
        ctx.waitTicks(ticks);
        turnEachFrame = false;
        ctx.runOnClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.yRotO = yaw;
        });
        long[] after = ctx.computeOnClient(mc -> new long[]{
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.views,
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.reusedViews
        });
        return new long[]{after[0] - before[0], after[1] - before[1]};
    }

    // the main render target as it is after the last frame, as ARGB pixels
    private static int[] captureFrame(ClientGameTestContext ctx) {
        java.util.concurrent.CompletableFuture<int[]> done = new java.util.concurrent.CompletableFuture<>();
        ctx.runOnClient(mc -> net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                int[] pixels = new int[image.getWidth() * image.getHeight()];
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        pixels[x + y * image.getWidth()] = image.getPixel(x, y);
                    }
                }
                done.complete(pixels);
            }
        }));
        ctx.waitFor(mc -> done.isDone(), 100);
        return done.join();
    }

    // pixels with a color channel differing by more than 8
    private static int countDifferingPixels(int[] a, int[] b) {
        int count = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            for (int shift = 0; shift < 24; shift += 8) {
                if (Math.abs(((a[i] >> shift) & 0xFF) - ((b[i] >> shift) & 0xFF)) > 8) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /**
     * Portal views rendered and portals skipped as hidden during the given ticks.
     */
    private static long[] countViews(ClientGameTestContext ctx, int ticks) {
        long[] before = ctx.computeOnClient(mc -> new long[]{
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.views,
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.hiddenPortals
        });
        ctx.waitTicks(ticks);
        long[] after = ctx.computeOnClient(mc -> new long[]{
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.views,
            qouteall.imm_ptl.core.render.PortalViewRenderer.Stats.hiddenPortals
        });
        return new long[]{after[0] - before[0], after[1] - before[1]};
    }

    /**
     * The dimension stack settings when creating a world, through the UI: the create world screen's "More" tab
     * has the dimension stack button (MixinCreateWorldScreenMoreTab_CVB); in the dimension stack screen the stack
     * is enabled (default entries: bright void, bright skyland, overworld, nether) and confirmed; then the world is
     * created. The new world must have the stack's global portals (overworld connected to the dimensions above
     * and below).
     */
    private void dimStackWorldCreation(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.openFresh(
            mc, () -> mc.gui.setScreen(new TitleScreen())));
        ctx.waitForScreen(net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.class);
        ctx.runOnClient(mc -> {
            var screen = (net.minecraft.client.gui.screens.worldselection.CreateWorldScreen) mc.gui.screen();
            screen.getUiState().setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            try {
                var field = net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.class
                    .getDeclaredField("tabNavigationBar");
                field.setAccessible(true);
                // tabs: game, world, more
                ((net.minecraft.client.gui.components.tabs.TabNavigationBar) field.get(screen)).selectTab(2, false);
            }
            catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTick();
        ctx.clickScreenButton("imm_ptl.altius_screen_button");
        ctx.waitForScreen(qouteall.imm_ptl.peripheral.dim_stack.DimStackScreen.class);
        ctx.clickScreenButton("imm_ptl.altius_toggle_false"); // enable
        ctx.waitTicks(2);
        screenshot(ctx, "dim_stack_screen");
        ctx.clickScreenButton("imm_ptl.finish");
        ctx.waitForScreen(net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.class);
        int entries = ctx.computeOnClient(mc -> {
            var info = qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement.dimStackToApply;
            return info == null ? -1 : info.entries.size();
        });
        check("dim stack: the screen's result is set for the new world", entries == 4, "entries " + entries);

        ctx.clickScreenButton("selectWorld.create");
        boolean joined = waitFor(ctx, 2400, () -> ctx.computeOnClient(mc ->
            mc.level != null && mc.player != null && mc.gui.screen() == null));
        check("dim stack: the world was created and joined", joined,
            ctx.computeOnClient(mc -> "screen " + (mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName())));
        if (!joined) {
            return;
        }
        boolean portals = waitFor(ctx, 200, () -> ctx.computeOnClient(mc ->
            GlobalPortalStorage.getGlobalPortals(mc.level).size() >= 2));
        String detail = ctx.computeOnClient(mc -> GlobalPortalStorage.getGlobalPortals(mc.level).stream()
            .map(p -> p.getDestDim().identifier().toString()).toList().toString());
        check("dim stack: the overworld has the stack's portals to the dimensions above and below", portals, detail);
        check("dim stack: they lead to the nether and the bright skyland",
            detail.contains("minecraft:the_nether") && detail.contains("skyland"), detail);
        ctx.waitTicks(40);
        screenshot(ctx, "dim_stack_world");

        // the stack's portals are at the build limits: the sky above and the bottom of the world.
        // Each pose is shot twice, 3 ticks apart, into a/ and b/: large differences with a still camera = flicker.
        runIntegratedServerCommand(ctx, "time set noon");
        runIntegratedServerCommand(ctx, "gamerule doDaylightCycle false");
        runIntegratedServerCommand(ctx, "fill -4 -63 -4 4 -52 4 minecraft:air");
        for (String[] pose : new String[][]{
            {"dim_stack_up_from_surface", "0 100 0 0 -70"},
            {"dim_stack_up_high", "0 290 0 0 -60"},
            {"dim_stack_up_close", "0 316 0 0 -30"},
            {"dim_stack_level_under_ceiling", "0 318 0 0 0"},
            {"dim_stack_horizontal_high", "0 250 0 0 0"},
            {"dim_stack_down_bottom", "0 -58 0 0 70"},
            {"dim_stack_level_over_floor", "0 -62 0 0 0"},
            // the world-sized floor portal must not show the nether beyond the render distance
            {"dim_stack_horizon", "0 150 0 0 8"},
            // in lava under the nether's ceiling portal: the portal must be in the lava fog too
            {"dim_stack_nether_lava_up", "in minecraft:the_nether 0 119 0 0 -60"},
        }) {
            if (pose[1].startsWith("in ")) {
                runIntegratedServerCommand(ctx, "execute in minecraft:the_nether run fill -3 115 -3 3 121 3 minecraft:lava");
                runIntegratedServerCommand(ctx, "execute in minecraft:the_nether run fill -3 122 -3 3 127 3 minecraft:air");
                String[] parts = pose[1].split(" ", 3);
                runIntegratedServerCommand(ctx, "execute in " + parts[1] + " run tp @p " + parts[2]);
            }
            else {
                runIntegratedServerCommand(ctx, "execute in minecraft:overworld run tp @p " + pose[1]);
            }
            ctx.runOnClient(mc -> {
                mc.player.getAbilities().flying = true;
                mc.player.onUpdateAbilities();
            });
            ctx.waitTicks(100);
            ctx.takeScreenshot(TestScreenshotOptions.of(pose[0]).disableCounterPrefix().withDestinationDir(out.resolve("a")));
            ctx.waitTicks(3);
            ctx.takeScreenshot(TestScreenshotOptions.of(pose[0]).disableCounterPrefix().withDestinationDir(out.resolve("b")));
        }
    }

    /**
     * Runs a command on the server as the given entity and returns its messages.
     * An exception inside the command shows as the "command.failed" message.
     */
    private static List<net.minecraft.network.chat.Component> runCapturing(
        net.minecraft.server.MinecraftServer s, net.minecraft.world.entity.Entity executor, String command
    ) {
        List<net.minecraft.network.chat.Component> messages = new ArrayList<>();
        net.minecraft.commands.CommandSource recorder = new net.minecraft.commands.CommandSource() {
            @Override public void sendSystemMessage(net.minecraft.network.chat.Component message) { messages.add(message); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        var source = s.createCommandSourceStack().withSource(recorder).withEntity(executor)
            .withPosition(executor.position()).withLevel((ServerLevel) executor.level());
        s.getCommands().performPrefixedCommand(source, command);
        return messages;
    }

    private static boolean isTranslation(net.minecraft.network.chat.Component c, String keyPrefix) {
        return c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
            && t.getKey().startsWith(keyPrefix);
    }

    // a test portal at (800.5, y + 2, 800.5) facing +Z, 2 x 3, leading 20 blocks to +X
    private static Portal spawnCommandTestPortal(net.minecraft.server.MinecraftServer s, int y) {
        ServerLevel level = s.overworld();
        Portal p = Portal.ENTITY_TYPE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        p.setOriginPos(new Vec3(800.5, y + 2, 800.5));
        p.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 2, 3);
        p.setDestinationDimension(Level.OVERWORLD);
        p.setDestination(new Vec3(820.5, y + 2, 800.5));
        qouteall.imm_ptl.core.McHelper.spawnServerEntity(p);
        return p;
    }

    private static void removeCommandTestPortals(net.minecraft.server.MinecraftServer s) {
        for (ServerLevel level : s.getAllLevels()) {
            level.getEntitiesOfClass(Portal.class, new AABB(700, -64, 700, 900, 320, 900)).forEach(e -> e.discard());
            for (Portal global : List.copyOf(GlobalPortalStorage.getGlobalPortals(level))) {
                qouteall.imm_ptl.core.api.PortalAPI.removeGlobalPortal(level, global);
            }
        }
    }

    // an argument value for a command node: the type's first example, or a value for types without examples
    private static String sampleArgument(com.mojang.brigadier.tree.ArgumentCommandNode<?, ?> node) {
        var type = node.getType();
        if (type instanceof net.minecraft.commands.arguments.DimensionArgument) {
            return "minecraft:overworld";
        }
        if (type instanceof net.minecraft.commands.arguments.EntityArgument) {
            return "@s";
        }
        if (type instanceof com.mojang.brigadier.arguments.IntegerArgumentType t) {
            return String.valueOf(Math.min(Math.max(2, t.getMinimum()), t.getMaximum()));
        }
        if (type instanceof com.mojang.brigadier.arguments.DoubleArgumentType t) {
            return String.valueOf(Math.min(Math.max(2.0, t.getMinimum()), t.getMaximum()));
        }
        if (type instanceof com.mojang.brigadier.arguments.FloatArgumentType t) {
            return String.valueOf(Math.min(Math.max(2.0f, t.getMinimum()), t.getMaximum()));
        }
        var examples = type.getExamples();
        if (!examples.isEmpty()) {
            return examples.iterator().next();
        }
        if (type instanceof com.mojang.brigadier.arguments.StringArgumentType) {
            return "test";
        }
        if (type instanceof net.minecraft.commands.arguments.ComponentArgument) {
            return "\"test\"";
        }
        return "1";
    }

    private static void collectCommands(
        com.mojang.brigadier.tree.CommandNode<net.minecraft.commands.CommandSourceStack> node,
        String prefix, List<String> out
    ) {
        for (var child : node.getChildren()) {
            String part = child instanceof com.mojang.brigadier.tree.LiteralCommandNode<?> literal
                ? literal.getLiteral()
                : sampleArgument((com.mojang.brigadier.tree.ArgumentCommandNode<?, ?>) child);
            String command = prefix + " " + part;
            if (child.getCommand() != null) {
                out.add(command);
            }
            if (child.getRedirect() == null) {
                collectCommands(child, command, out);
            }
        }
    }

    /**
     * Every executable form of /portal (except the debug subtree), with the example values of its argument types,
     * run as a fresh test portal. No command may throw. (Commands rejected because an example value doesn't fit,
     * or because they need a player, are listed in portal_commands.txt, not failed.)
     * Then a few commands with their effects checked.
     */
    private void portalCommands(ClientGameTestContext ctx, TestServerContext srv) {
        int y = groundY(srv, Level.OVERWORLD, 800, 800);
        List<String> commands = srv.computeOnServer(s -> {
            var root = s.getCommands().getDispatcher().getRoot().getChild("portal");
            List<String> out = new ArrayList<>();
            for (var child : root.getChildren()) {
                if (child.getName().equals("debug")) {
                    continue;
                }
                List<String> sub = new ArrayList<>();
                if (child.getCommand() != null) {
                    sub.add("portal " + child.getName());
                }
                collectCommands(child, "portal " + child.getName(), sub);
                out.addAll(sub);
            }
            return out;
        });

        StringBuilder log = new StringBuilder();
        List<String> threw = new ArrayList<>();
        int rejected = 0;
        for (String command : commands) {
            String outcome = srv.computeOnServer(s -> {
                removeCommandTestPortals(s);
                Portal portal = spawnCommandTestPortal(s, y);
                var messages = runCapturing(s, portal, command);
                String asPortal = messages.stream().map(m -> m.getString()).reduce("", (a, b) -> a + b);
                if (asPortal.contains("invoked by player") || asPortal.contains("A player is required")
                    || asPortal.contains("No player was found")) {
                    // as the player standing in front of the portal, looking at it
                    ServerPlayer player = serverPlayer(s);
                    player.teleportTo(s.overworld(), 801.0, y + 1, 804.0, java.util.Set.of(), 180, 15, true);
                    messages = runCapturing(s, player, command);
                    messages.add(0, net.minecraft.network.chat.Component.literal("[as player]"));
                }
                String text = messages.stream().map(m -> m.getString()).reduce((a, b) -> a + " | " + b).orElse("");
                if (messages.stream().anyMatch(m -> isTranslation(m, "command.failed"))) {
                    return "EXCEPTION " + text;
                }
                if (messages.stream().anyMatch(m -> isTranslation(m, "command.unknown") || isTranslation(m, "argument.")
                    || isTranslation(m, "parsing.") || isTranslation(m, "permissions.requires"))) {
                    return "rejected " + text;
                }
                return "ok " + text;
            });
            ctx.waitTick();
            // some commands open a screen on the client (e.g. dimension_stack)
            ctx.runOnClient(mc -> {
                if (mc.gui.screen() != null) {
                    mc.gui.setScreen(null);
                }
            });
            if (outcome.startsWith("EXCEPTION")) {
                threw.add(command);
            }
            else if (outcome.startsWith("rejected")) {
                rejected++;
            }
            log.append(command).append("  ->  ").append(outcome.replace('\n', ' ')).append('\n');
        }
        srv.runOnServer(s -> {
            removeCommandTestPortals(s);
            // commands run as the player may have made portals at the example position 0 0 0
            for (ServerLevel level : s.getAllLevels()) {
                level.getEntitiesOfClass(Portal.class, new AABB(-50, -64, -50, 50, 320, 50)).forEach(e -> e.discard());
            }
            serverPlayer(s).teleportTo(s.overworld(), 801.0, y + 1, 804.0, java.util.Set.of(), 180, 15, true);
        });
        ctx.waitTicks(20);
        try {
            Files.writeString(out.resolve("portal_commands.txt"), log.toString());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        check("portal commands: " + commands.size() + " command forms ran, none threw an exception ("
                + rejected + " rejected their example arguments, see portal_commands.txt)",
            commands.size() > 50 && threw.isEmpty(), "threw: " + threw);

        // effects
        AABB box = new AABB(790, y - 5, 790, 830, y + 10, 810);
        String effects = srv.computeOnServer(s -> {
            List<String> problems = new ArrayList<>();
            Portal portal = spawnCommandTestPortal(s, y);
            runCapturing(s, portal, "portal set_portal_size 3 4");
            if (portal.getWidth() != 3 || portal.getHeight() != 4) {
                problems.add("set_portal_size: " + portal.getWidth() + "x" + portal.getHeight());
            }
            runCapturing(s, portal, "portal set_portal_destination minecraft:the_nether 10 70 10");
            if (portal.getDestDim() != Level.NETHER || portal.getDestPos().distanceTo(new Vec3(10, 70, 10)) > 0.01) {
                problems.add("set_portal_destination: " + portal.getDestDim() + " " + portal.getDestPos());
            }
            runCapturing(s, portal, "portal set_portal_destination minecraft:overworld 820.5 " + (y + 2) + " 800.5");
            runCapturing(s, portal, "portal complete_bi_way_bi_faced_portal");
            int cluster = s.overworld().getEntitiesOfClass(Portal.class, box).size();
            if (cluster != 4) {
                problems.add("complete_bi_way_bi_faced_portal: " + cluster + " portals");
            }
            runCapturing(s, portal, "portal eradicate_portal_cluster");
            int afterEradicate = s.overworld().getEntitiesOfClass(Portal.class, box).size();
            if (afterEradicate != 0) {
                problems.add("eradicate_portal_cluster: " + afterEradicate + " portals left");
            }
            Portal toGlobal = spawnCommandTestPortal(s, y);
            int globalsBefore = GlobalPortalStorage.getGlobalPortals(s.overworld()).size();
            runCapturing(s, toGlobal, "portal global convert_normal_portal_to_global_portal");
            int globalsAfter = GlobalPortalStorage.getGlobalPortals(s.overworld()).size();
            if (globalsAfter != globalsBefore + 1) {
                problems.add("convert_normal_portal_to_global_portal: global portals " + globalsBefore + " -> " + globalsAfter);
            }
            removeCommandTestPortals(s);
            return String.join("; ", problems);
        });
        check("portal commands: set_portal_size, set_portal_destination, complete_bi_way_bi_faced_portal, "
            + "eradicate_portal_cluster, convert_normal_portal_to_global_portal have their effects", effects.isEmpty(), effects);
    }

    // for a world created through the UI (no TestServerContext)
    private static void runIntegratedServerCommand(ClientGameTestContext ctx, String command) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
        });
        ctx.waitTicks(2);
    }

    private static ServerPlayer serverPlayer(net.minecraft.server.MinecraftServer s) {
        return s.getPlayerList().getPlayers().get(0);
    }

    // uses an item on a block face like a player (server side, with the player's main hand)
    private static void useItemOn(TestServerContext srv, ItemStack stack, BlockPos pos, Direction face) {
        srv.runOnServer(s -> {
            ServerPlayer p = serverPlayer(s);
            p.setItemInHand(InteractionHand.MAIN_HAND, stack);
            Vec3 hit = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
            p.getMainHandItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, pos, false)));
        });
    }

    // saves the main render target as it is after the last normal frame (with the gizmos of that frame)
    private void grabLastFrame(ClientGameTestContext ctx, String name) {
        Path file = out.resolve(name + ".png");
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        ctx.runOnClient(mc -> net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(file);
                done.complete(null);
            }
            catch (IOException e) {
                done.completeExceptionally(e);
            }
        }));
        ctx.waitFor(mc -> done.isDone(), 100);
    }

    private static int countPortals(TestServerContext srv, ResourceKey<Level> dim, AABB box) {
        return srv.computeOnServer(s -> s.getLevel(dim).getEntitiesOfClass(Portal.class, box).size());
    }

    // a vertical frame in the plane z=frameZ, inner area 2 wide x 3 high, bottom frame row at y
    private static void buildFrame(TestServerContext srv, int x0, int y, int frameZ, String block) {
        srv.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x0, y, frameZ, x0 + 3, y + 4, frameZ, block));
        srv.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x0 + 1, y + 1, frameZ, x0 + 2, y + 3, frameZ));
    }

    /**
     * Leashes are triangle strips; EntityClipping cuts every triangle of a strip at the portal plane and joins the
     * rest with degenerate triangles. A strip along X from x=-1 to x=1, clipped by the plane x >= 0:
     * no triangle may reach x < 0, and the kept area must be the half of the strip.
     */
    private void stripClipping() {
        List<float[]> out = new ArrayList<>();
        com.mojang.blaze3d.vertex.VertexConsumer recorder = new com.mojang.blaze3d.vertex.VertexConsumer() {
            @Override public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) { out.add(new float[]{x, y, z}); return this; }
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) { return this; }
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setColor(int color) { return this; }
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) { return this; }
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) { return this; }
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) { return this; }
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv3(float u, float v) { return this; }
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) { return this; }
            @Override public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) { return this; }
        };
        var consumer = new qouteall.imm_ptl.core.render.EntityClipping.ClippingVertexConsumer(
            recorder, new float[]{1, 0, 0, 0}, true
        );
        // 8 segments like a leash: pairs of vertices at y=0 and y=0.1
        for (int k = 0; k <= 8; k++) {
            float x = -1 + k * 0.25f;
            consumer.addVertex(x, 0, 0).setColor(0xFFFFFFFF);
            consumer.addVertex(x, 0.1f, 0).setColor(0xFFFFFFFF);
        }
        consumer.flush();

        float minX = Float.MAX_VALUE;
        for (float[] v : out) {
            minX = Math.min(minX, v[0]);
        }
        // area of the strip's triangles (degenerate ones add nothing)
        double area = 0;
        for (int i = 0; i + 2 < out.size(); i++) {
            float[] a = out.get(i), b = out.get(i + 1), c = out.get(i + 2);
            double ux = b[0] - a[0], uy = b[1] - a[1], vx = c[0] - a[0], vy = c[1] - a[1];
            area += Math.abs(ux * vy - uy * vx) / 2;
        }
        check("strip clipping: nothing is left behind the plane", !out.isEmpty() && minX > -1e-4,
            "vertices " + out.size() + ", min x " + minX);
        check("strip clipping: the part in front of the plane is kept", Math.abs(area - 0.1) < 1e-3,
            String.format(Locale.ROOT, "area %.4f, expected 0.1000", area));
    }

    /**
     * Command stick: using it runs its command (server side, like a right click).
     */
    private void commandStick(ClientGameTestContext ctx, TestServerContext srv) {
        int y = groundY(srv, Level.OVERWORLD, 600, 600);
        check("command stick: built-in command sticks are registered",
            !qouteall.imm_ptl.peripheral.CommandStickItem.BUILT_IN_COMMAND_STICK_TYPES.isEmpty(), "");
        srv.runOnServer(s -> {
            ServerPlayer p = serverPlayer(s);
            ItemStack stack = new ItemStack(qouteall.imm_ptl.peripheral.CommandStickItem.instance);
            stack.set(qouteall.imm_ptl.peripheral.CommandStickItem.COMPONENT_TYPE, new qouteall.imm_ptl.peripheral.CommandStickItem.Data(
                String.format(Locale.ROOT, "/setblock 600 %d 600 minecraft:gold_block", y + 2),
                "imm_ptl.command.test", List.of()
            ));
            p.setItemInHand(InteractionHand.MAIN_HAND, stack);
            qouteall.imm_ptl.peripheral.CommandStickItem.instance.use(p.level(), p, InteractionHand.MAIN_HAND);
        });
        ctx.waitTicks(5);
        boolean placed = srv.computeOnServer(s ->
            s.overworld().getBlockState(new BlockPos(600, y + 2, 600)).is(Blocks.GOLD_BLOCK));
        check("command stick: using it runs its command", placed, "");
        // the item name comes from the stick's data (client side)
        String name = ctx.computeOnClient(mc -> mc.player.getMainHandItem().getHoverName().getString());
        check("command stick: the client shows its name", name != null && !name.isEmpty(), "name '" + name + "'");
        srv.runOnServer(s -> serverPlayer(s).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
    }

    /**
     * Flint and steel on a glass wall creates a mirror covering the wall.
     */
    private void breakableMirror(ClientGameTestContext ctx, TestServerContext srv) {
        int y = groundY(srv, Level.OVERWORLD, 620, 630);
        srv.runCommand(String.format(Locale.ROOT, "fill 620 %d 630 622 %d 630 minecraft:glass", y, y + 2));
        srv.runCommand(String.format(Locale.ROOT, "tp Player0 621.5 %d 626.5 0 0", y));
        ctx.waitTicks(10);
        useItemOn(srv, new ItemStack(Items.FLINT_AND_STEEL), new BlockPos(621, y + 1, 630), Direction.NORTH);
        ctx.waitTicks(10);
        int mirrors = srv.computeOnServer(s -> s.overworld().getEntitiesOfClass(
            qouteall.imm_ptl.core.portal.BreakableMirror.class, new AABB(618, y - 2, 628, 625, y + 5, 633)).size());
        check("mirror: flint and steel on glass creates a mirror", mirrors >= 1, "mirrors " + mirrors);
        ctx.waitTicks(20);
        screenshot(ctx, "tools_mirror");
    }

    /**
     * Two frames of portal helper blocks; flint and steel on one links them with portals.
     */
    private void portalHelper(ClientGameTestContext ctx, TestServerContext srv) {
        int y = groundY(srv, Level.OVERWORLD, 640, 650);
        String helper = "immersive_portals:portal_helper";
        buildFrame(srv, 640, y, 650, helper);
        buildFrame(srv, 660, y, 650, helper);
        srv.runCommand(String.format(Locale.ROOT, "tp Player0 641.5 %d 646.5 0 0", y + 1));
        ctx.waitTicks(10);
        useItemOn(srv, new ItemStack(Items.FLINT_AND_STEEL), new BlockPos(641, y, 650), Direction.UP);
        AABB box = new AABB(630, y - 5, 640, 675, y + 10, 660);
        boolean created = waitFor(ctx, 200, () -> countPortals(srv, Level.OVERWORLD, box) >= 2);
        check("portal helper: igniting a helper frame creates portals", created,
            "portals " + countPortals(srv, Level.OVERWORLD, box));
        ctx.waitTicks(20);
        screenshot(ctx, "tools_portal_helper");
    }

    /**
     * Portal wand, create mode: place the 3 corners of both sides through the client code
     * (as the right clicks do), then the client sends the portal to the server.
     * Then hold the wand in the other modes looking at the portal (their cursor update and rendering run).
     */
    private void portalWand(ClientGameTestContext ctx, TestServerContext srv) {
        int y = groundY(srv, Level.OVERWORLD, 700, 700);
        srv.runCommand(String.format(Locale.ROOT, "tp Player0 706.0 %d 694.0 0 0", y));
        srv.runOnServer(s -> {
            ItemStack wand = new ItemStack(qouteall.imm_ptl.peripheral.wand.PortalWandItem.instance);
            wand.set(qouteall.imm_ptl.peripheral.wand.PortalWandItem.COMPONENT_TYPE, qouteall.imm_ptl.peripheral.wand.PortalWandItem.Mode.CREATE_PORTAL);
            serverPlayer(s).setItemInHand(InteractionHand.MAIN_HAND, wand);
        });
        ctx.waitTicks(20);
        Vec3[] corners = {
            new Vec3(700, y + 1, 700), new Vec3(702, y + 1, 700), new Vec3(700, y + 4, 700),
            new Vec3(710, y + 1, 700), new Vec3(712, y + 1, 700), new Vec3(710, y + 4, 700)
        };
        for (int i = 0; i < corners.length; i++) {
            Vec3 corner = corners[i];
            boolean ok = ctx.computeOnClient(mc -> qouteall.imm_ptl.peripheral.wand.ClientPortalWandPortalCreation
                .protoPortal.tryPlaceCursor(Level.OVERWORLD, corner));
            if (!ok) {
                check("portal wand: corner " + i + " accepted", false, corner.toString());
                return;
            }
            if (i == 2) {
                // the first side is complete: its outline and the plane constraint are rendered
                ctx.waitTicks(10);
                screenshot(ctx, "tools_wand_first_side");
                // Fabric's screenshots render an extra frame without the per-frame gizmo collection, so the wand's
                // lines (gizmos) are not in them. Save the last normal frame instead.
                grabLastFrame(ctx, "tools_wand_first_side_frame");
                ctx.runOnClient(mc -> mc.debugEntries.toggleStatus(
                    net.minecraft.client.gui.components.debug.DebugScreenEntries.CHUNK_BORDERS));
                // the wand emits its lines as gizmos (MixinDebugRenderer); collect them here to count them
                String info = ctx.computeOnClient(mc -> {
                    ItemStack held = mc.player.getMainHandItem();
                    net.minecraft.gizmos.SimpleGizmoCollector collector = new net.minecraft.gizmos.SimpleGizmoCollector();
                    Vec3 cam = mc.gameRenderer.mainCamera().position();
                    try (var ignored = net.minecraft.gizmos.Gizmos.withCollector(collector)) {
                        qouteall.imm_ptl.peripheral.wand.PortalWandItem.clientRender(
                            mc.player, held, new com.mojang.blaze3d.vertex.PoseStack(), cam.x, cam.y, cam.z
                        );
                    }
                    var gizmos = collector.drainGizmos();
                    Vec3 sideCenter = new Vec3(701, y + 2.5, 700);
                    long near = gizmos.stream()
                        .filter(g -> g.gizmo() instanceof net.minecraft.gizmos.LineGizmo line
                            && line.start().distanceTo(sideCenter) < 3)
                        .count();
                    String first = gizmos.isEmpty() ? "-" : gizmos.get(0).gizmo().toString();
                    return (held.getItem() == qouteall.imm_ptl.peripheral.wand.PortalWandItem.instance)
                        + "," + gizmos.size() + "," + near + "," + cam + "," + first;
                });
                String[] parts = info.split(",", 4);
                check("portal wand: the first side's outline is drawn at the side",
                    parts[0].equals("true") && Long.parseLong(parts[2]) > 0,
                    "holding the wand " + parts[0] + ", lines " + parts[1] + ", near the side " + parts[2]
                        + ", camera/first " + parts[3]);
            }
        }
        boolean complete = ctx.computeOnClient(mc -> qouteall.imm_ptl.peripheral.wand.ClientPortalWandPortalCreation.protoPortal.isComplete());
        check("portal wand: the proto portal is complete after 6 corners", complete, "");
        ctx.runOnClient(mc -> qouteall.imm_ptl.peripheral.wand.ClientPortalWandPortalCreation.finish());
        AABB box = new AABB(695, y - 2, 695, 717, y + 8, 705);
        boolean created = waitFor(ctx, 100, () -> countPortals(srv, Level.OVERWORLD, box) >= 2);
        check("portal wand: finishing creates the portals on the server", created,
            "portals " + countPortals(srv, Level.OVERWORLD, box));

        for (var mode : List.of(qouteall.imm_ptl.peripheral.wand.PortalWandItem.Mode.DRAG_PORTAL, qouteall.imm_ptl.peripheral.wand.PortalWandItem.Mode.COPY_PORTAL)) {
            srv.runOnServer(s -> serverPlayer(s).getMainHandItem().set(qouteall.imm_ptl.peripheral.wand.PortalWandItem.COMPONENT_TYPE, mode));
            ctx.waitTicks(20);
            screenshot(ctx, "tools_wand_" + mode.name().toLowerCase(Locale.ROOT));
        }
        srv.runOnServer(s -> serverPlayer(s).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
    }

    /**
     * The test datapack (written into the save before it's opened again): a portal with a gold block frame,
     * lit by using a stick on the frame, leading to the nether (1:1, the frame is generated there).
     */
    private void writeTestDatapack() {
        Path pack = worldSave.getSaveDirectory().resolve("datapacks").resolve("immptl_test");
        try {
            Files.createDirectories(pack.resolve("data/immptl_test/custom_portal_generation"));
            Files.writeString(pack.resolve("pack.mcmeta"),
                "{\"pack\": {\"description\": \"ImmPtl feature test\", \"min_format\": 121, \"max_format\": 121}}\n");
            Files.writeString(pack.resolve("data/immptl_test/custom_portal_generation/gold_portal.json"), """
                {
                  "schema_version": "imm_ptl:v1",
                  "from": ["minecraft:overworld"],
                  "to": "minecraft:the_nether",
                  "space_ratio_from": 1,
                  "space_ratio_to": 1,
                  "form": {
                    "type": "imm_ptl:classical",
                    "from_frame_block": "minecraft:gold_block",
                    "area_block": "minecraft:air",
                    "to_frame_block": "minecraft:gold_block",
                    "generate_frame_if_not_found": true
                  },
                  "trigger": {"type": "imm_ptl:use_item", "item": "minecraft:stick"}
                }
                """);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void customPortalGeneration(ClientGameTestContext ctx, TestServerContext srv) {
        int y = groundY(srv, Level.OVERWORLD, 760, 760);
        buildFrame(srv, 760, y, 760, "minecraft:gold_block");
        srv.runCommand(String.format(Locale.ROOT, "tp Player0 761.5 %d 756.5 0 0", y + 1));
        ctx.waitTicks(20);
        useItemOn(srv, new ItemStack(Items.STICK), new BlockPos(761, y, 760), Direction.UP);
        AABB box = new AABB(755, y - 2, 755, 770, y + 8, 765);
        boolean created = waitFor(ctx, 300, () -> countPortals(srv, Level.OVERWORLD, box) >= 1);
        check("custom portal gen: using a stick on the gold frame creates a portal", created,
            "portals " + countPortals(srv, Level.OVERWORLD, box));
        if (created) {
            ResourceKey<Level> dest = srv.computeOnServer(s -> s.overworld()
                .getEntitiesOfClass(Portal.class, box).get(0).getDestDim());
            check("custom portal gen: the portal leads to the nether", dest == Level.NETHER, "dest " + dest.identifier());
            ctx.waitTicks(60);
            screenshot(ctx, "tools_custom_portal_gen");
        }
    }

    // chunks within the render distance of the player that are not in the client chunk cache (max 10 listed)
    private static List<String> missingChunksAroundPlayer(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            List<String> missing = new ArrayList<>();
            int r = mc.options.getEffectiveRenderDistance();
            int cx = mc.player.chunkPosition().x();
            int cz = mc.player.chunkPosition().z();
            for (int x = cx - r; x <= cx + r && missing.size() < 10; x++) {
                for (int z = cz - r; z <= cz + r && missing.size() < 10; z++) {
                    if (!mc.level.getChunkSource().hasChunk(x, z)) {
                        missing.add(x + "," + z);
                    }
                }
            }
            return missing;
        });
    }

    // the Y component of the main camera's up vector, from its view rotation matrix (includes the animation delta)
    private static double cameraUpY(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            org.joml.Matrix4f viewRotation = mc.gameRenderer.mainCamera().getViewRotationMatrix(new org.joml.Matrix4f());
            return (double) viewRotation.invert().transformDirection(new org.joml.Vector3f(0, 1, 0)).y;
        });
    }

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
