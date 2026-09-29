package qouteall.imm_ptl.core.render;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ScaleUtils;
import qouteall.imm_ptl.core.commands.PortalCommand;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;

/**
 * In third person view the camera may be on the other side of a portal than the player.
 * If view bobbing makes the camera go through a portal before the actual player goes through it,
 * the camera is also on the other side.
 * In these cases the whole view must show the portal's destination, seen from the transformed camera position.
 * <p>
 * This class computes the cross-portal view ({@link #getCrossPortalView}).
 * {@code PortalViewRenderer.renderPortalViews} renders it (with the portals seen from there) into a window-sized
 * target, and {@code MixinGameRenderer} copies that over the main image after the main level render,
 * before the hand and HUD. (The main level is still rendered; it's only replaced.)
 */
public class CrossPortalViewRendering {
    public static final Minecraft client = Minecraft.getInstance();

    /**
     * @param portal         the portal between the player's head and the camera
     * @param cameraPos      the camera position in the destination dimension
     * @param worldRenderInfo the render info for rendering the destination (camera transformation of the portal)
     */
    public static record CrossPortalView(
        Portal portal, Vec3 cameraPos, WorldRenderInfo worldRenderInfo
    ) {}

    /**
     * Must be called after the main camera is updated for this frame.
     *
     * @return null if the camera is on the same side of all portals as the player's head
     */
    public static @Nullable CrossPortalView getCrossPortalView(Camera mainCamera) {
        if (!IPGlobal.enableCrossPortalView) {
            return null;
        }

        Entity cameraEntity = client.getCameraEntity();
        ClientLevel level = client.level;
        if (cameraEntity == null || level == null || !mainCamera.isInitialized()) {
            return null;
        }

        Vec3 realCameraPos = mainCamera.position();
        Vec3 isometricAdjustedOriginalCameraPos =
            TransformationManager.getIsometricAdjustedCameraPos(mainCamera);

        Vec3 physicalPlayerHeadPos = ClientTeleportationManager.getPlayerEyePos(RenderStates.getPartialTick());

        Pair<Portal, Vec3> portalHit = PortalCommand.raytracePortals(
            level, physicalPlayerHeadPos, isometricAdjustedOriginalCameraPos, true
        ).orElse(null);

        if (portalHit == null) {
            return null;
        }

        Portal portal = portalHit.getFirst();
        Vec3 hitPos = portalHit.getSecond();

        if (!portal.canTeleportEntity(cameraEntity)) {
            return null;
        }

        ClientLevel destWorld = ClientWorldLoader.getOptionalWorld(portal.getDestDim());
        if (destWorld == null) {
            return null;
        }

        Vec3 renderingCameraPos;

        if (isThirdPerson()) {
            double distance = getThirdPersonMaxDistance();

            Vec3 thirdPersonPos = realCameraPos.subtract(physicalPlayerHeadPos).normalize()
                .scale(distance).add(physicalPlayerHeadPos);

            renderingCameraPos = getThirdPersonCameraPos(thirdPersonPos, portal, hitPos);
        }
        else {
            renderingCameraPos = portal.transformPoint(realCameraPos);
        }

        WorldRenderInfo worldRenderInfo = new WorldRenderInfo.Builder()
            .setWorld(destWorld)
            .setCameraPos(renderingCameraPos)
            .setCameraTransformation(portal.getAdditionalCameraTransformation())
            .setOverwriteCameraTransformation(false)
            .setDescription(null)
            .setRenderDistance(client.options.getEffectiveRenderDistance())
            .setDoRenderHand(false)
            .setEnableViewBobbing(false)
            .build();

        return new CrossPortalView(portal, renderingCameraPos, worldRenderInfo);
    }

    private static boolean isThirdPerson() {
        return !client.options.getCameraType().isFirstPerson();
    }

    /**
     * Like the third person camera distance limiting in {@code Camera.getMaxZoom}, in the destination world.
     */
    private static Vec3 getThirdPersonCameraPos(Vec3 endPos, Portal portal, Vec3 startPos) {
        Vec3 rtStart = portal.transformPoint(startPos);
        Vec3 rtEnd = portal.transformPoint(endPos);
        assert client.getCameraEntity() != null;
        BlockHitResult blockHitResult = portal.getDestinationWorld().clip(
            new ClipContext(
                rtStart,
                rtEnd,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                client.getCameraEntity()
            )
        );

        if (blockHitResult.getType() == HitResult.Type.BLOCK) {
            return rtStart.add(rtEnd.subtract(rtStart).normalize().scale(
                getThirdPersonMaxDistance()
            ));
        }

        return blockHitResult.getLocation();
    }

    private static double getThirdPersonMaxDistance() {
        return 4.0d * ScaleUtils.computeThirdPersonScale(client.player);
    }
}
