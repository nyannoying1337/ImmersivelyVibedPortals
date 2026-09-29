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
        if (Boolean.getBoolean("imm_ptl.featureTest.skip")) {
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
            section("rotating portal", () -> rotatingPortal(ctx, srv));
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
