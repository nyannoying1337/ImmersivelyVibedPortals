package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.collision.PortalCollisionEntry;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalManipulation;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.Plane;

import java.util.WeakHashMap;

/**
 * Renders entities that are halfway through a portal on both sides of it.
 * <p>
 * An entity in dimension A that collides with a portal leading to dimension B has a "projection" in B:
 * the entity transformed by the portal. In 26.3 the projection is an extra {@link EntityRenderState}
 * added to B's {@link LevelRenderState#entityRenderStates} while B is extracted
 * (as the main view or as a portal view), see {@link #extractEntityProjections}.
 * Its position is moved by the portal transformation and the rotation/scaling of the portal
 * is applied around the entity origin when it's submitted (see {@link #applyProjectionTransformation}).
 * <p>
 * Clipping: the original entity is clipped by the outer clipping plane of the portals it touches
 * (the part that went through is invisible), the projection by the inner clipping plane of its portal
 * (the part that has not gone through yet is invisible). The planes are applied on the CPU, see {@link EntityClipping}.
 */
@Environment(EnvType.CLIENT)
public class CrossPortalEntityRenderer {
    private static final Minecraft client = Minecraft.getInstance();

    //there is no weak hash set
    private static final WeakHashMap<Entity, Object> collidedEntities = new WeakHashMap<>();

    /**
     * The render states of entity projections and the portal that transforms them.
     * Render states are created per extraction and are not reused, so they're weakly referenced.
     */
    private static final WeakHashMap<EntityRenderState, Portal> projectionStates = new WeakHashMap<>();

    public static void init() {
        IPGlobal.POST_CLIENT_TICK_EVENT.register(CrossPortalEntityRenderer::onClientTick);

        IPCGlobal.CLIENT_CLEANUP_EVENT.register(CrossPortalEntityRenderer::cleanUp);

        ClientWorldLoader.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT.register(dim -> cleanUp());
    }

    private static void cleanUp() {
        collidedEntities.clear();
        projectionStates.clear();
    }

    private static void onClientTick() {
        collidedEntities.entrySet().removeIf(entry -> {
            Entity entity = entry.getKey();
            return entity.isRemoved() || !((IEEntity) entity).ip_isCollidingWithPortal();
        });
    }

    public static void onEntityTickClient(Entity entity) {
        if (entity instanceof Portal) {
            return;
        }

        if (((IEEntity) entity).ip_isCollidingWithPortal()) {
            collidedEntities.put(entity, null);
        }
    }

    /**
     * Called after an entity is extracted into a render state (for the original entity and for projections).
     * Sets the outer clipping planes of the portals the entity is going through.
     */
    public static void onEntityExtracted(Entity entity, EntityRenderState state) {
        if (collidedEntities.isEmpty() || !collidedEntities.containsKey(entity)) {
            return;
        }
        if (!isCrossPortalRenderingEnabled()) {
            return;
        }
        PortalCollisionHandler collisionHandler = ((IEEntity) entity).ip_getPortalCollisionHandler();
        if (collisionHandler == null) {
            return;
        }
        java.util.List<Plane> planes = new java.util.ArrayList<>();
        for (PortalCollisionEntry e : collisionHandler.portalCollisions) {
            if (e.portal instanceof Mirror) {
                continue;
            }
            Plane outerClipping = e.portal.getOuterClipping();
            if (outerClipping != null) {
                planes.add(outerClipping);
            }
        }
        EntityClipping.setStatePlanes(state, planes.toArray(Plane[]::new));
    }

    private static boolean isCrossPortalRenderingEnabled() {
        if (IrisInterface.invoker.isIrisPresent()) {
            return false;
        }
        return IPGlobal.correctCrossPortalEntityRendering;
    }

    /**
     * Adds the projections of the entities that are halfway through a portal
     * leading into the level that is being extracted.
     * Called by the extractor of the current dimension after it extracted the visible entities
     * (see {@link qouteall.imm_ptl.core.mixin.client.render.MixinLevelExtractor_CrossPortalEntity}).
     * {@link Minecraft#level} and the game renderer's camera are the ones of the current view.
     */
    public static void extractEntityProjections(
        Camera camera, DeltaTracker deltaTracker, LevelRenderState output
    ) {
        if (!isCrossPortalRenderingEnabled()) {
            return;
        }
        if (client.level == null || collidedEntities.isEmpty()) {
            return;
        }

        ResourceKey<Level> currentDim = client.level.dimension();

        for (Entity entity : collidedEntities.keySet()) {
            PortalCollisionHandler collisionHandler = ((IEEntity) entity).ip_getPortalCollisionHandler();
            if (collisionHandler == null) {
                continue;
            }

            for (PortalCollisionEntry e : collisionHandler.portalCollisions) {
                Portal collidingPortal = e.portal;
                if (collidingPortal instanceof Mirror) {
                    continue;
                }
                if (collidingPortal.getDestDim() != currentDim) {
                    continue;
                }
                if (!shouldRenderProjection(entity, collidingPortal, camera.position())) {
                    continue;
                }

                float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(
                    !entity.level().tickRateManager().isEntityFrozen(entity)
                );
                EntityRenderState state = client.levelRenderer.entityRenderDispatcher()
                    .extractEntity(entity, partialTicks);

                // move the render state to the other side of the portal.
                // the lighting stays the one of the original position
                Vec3 newPos = collidingPortal.transformPoint(new Vec3(state.x, state.y, state.z));
                state.x = newPos.x;
                state.y = newPos.y;
                state.z = newPos.z;
                state.distanceToCameraSq = newPos.distanceToSqr(camera.position());

                // only the part that went through the portal is visible
                // (replaces the outer clipping set by onEntityExtracted, that's for the original)
                Plane innerClipping = collidingPortal.getInnerClipping();
                EntityClipping.setStatePlanes(state, innerClipping == null ? null : new Plane[]{innerClipping});

                if (collidingPortal.getScaling() != 1.0 || collidingPortal.getRotation() != null) {
                    projectionStates.put(state, collidingPortal);
                }

                output.entityRenderStates.add(state);
            }
        }
    }

    /**
     * Called by the entity render dispatcher after the pose stack is translated to the entity origin.
     * Applies the portal rotation and scaling to the projection of an entity.
     */
    public static void applyProjectionTransformation(EntityRenderState state, PoseStack poseStack) {
        if (projectionStates.isEmpty()) {
            return;
        }
        Portal portal = projectionStates.get(state);
        if (portal == null) {
            return;
        }

        float scaling = (float) portal.getScaling();
        poseStack.scale(scaling, scaling, scaling);

        if (portal.getRotation() != null) {
            poseStack.rotate(portal.getRotation().toMcQuaternion());
        }
    }

    private static boolean shouldRenderProjection(
        Entity entity, Portal collidingPortal, Vec3 cameraPos
    ) {
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();

            // correctly rendering it needs two clipping planes
            // use some rough check to work around
            if (Portal.isFlippedPortal(renderingPortal, collidingPortal)
                || Portal.isReversePortal(renderingPortal, collidingPortal)
            ) {
                return false;
            }

            Plane innerClipping = collidingPortal.getInnerClipping();
            boolean isHidden = innerClipping != null &&
                !innerClipping.isPointOnPositiveSide(cameraPos);
            if (renderingPortal != collidingPortal && isHidden) {
                return false;
            }

            Vec3 newEyePos = collidingPortal.transformPoint(McHelper.getEyePos(entity));
            Vec3 transformedEntityPos = newEyePos.subtract(McHelper.getEyeOffset(entity));
            AABB transformedBoundingBox = McHelper.getBoundingBoxWithMovedPosition(entity, transformedEntityPos);

            if (!PortalManipulation.isOtherSideBoxInside(transformedBoundingBox, renderingPortal)) {
                return false;
            }
        }

        if (entity instanceof LocalPlayer) {
            if (!IPGlobal.renderYourselfInPortal) {
                return false;
            }

            if (!collidingPortal.getDoRenderPlayer()) {
                return false;
            }

            if (client.options.getCameraType().isFirstPerson()) {
                // avoid rendering player too near and block view
                Vec3 newEyePos = collidingPortal.transformPoint(McHelper.getEyePos(entity));
                double dis = newEyePos.distanceTo(cameraPos);
                double valve = 0.5 + McHelper.lastTickPosOf(entity).distanceTo(entity.position());
                if (collidingPortal.getScaling() > 1) {
                    valve *= collidingPortal.getScaling();
                }
                if (dis < valve) {
                    return false;
                }

                AABB transformedBoundingBox =
                    Helper.transformBox(RenderStates.originalPlayerBoundingBox, collidingPortal::transformPoint);
                if (transformedBoundingBox.contains(cameraPos)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Used by {@link qouteall.imm_ptl.core.mixin.client.render.MixinCamera} to make the camera "detached"
     * so that the player is extracted when rendering a portal view.
     */
    public static boolean shouldRenderPlayerDefault() {
        if (!IPGlobal.renderYourselfInPortal) {
            return false;
        }
        if (!WorldRenderInfo.isRendering()) {
            return false;
        }
        LocalPlayer player = client.player;
        if (player == null) {
            return false;
        }

        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            if (renderingPortal instanceof Mirror) {
                // if the camera pos is too close to the mirror,
                // it will show the inside of the player head.
                // avoid rendering player in this case.
                float width = player.getBbWidth();
                if (renderingPortal.getDistanceToNearestPointInPortal(player.getEyePosition()) < width * 0.8) {
                    return false;
                }
            }
        }

        return client.level == player.level();
    }

    /**
     * Called from the entity render dispatcher's shouldRender (during extraction).
     * In a portal view, the entities behind the portal destination are not extracted.
     * (Parts of entities that cross the portal plane are clipped by {@link PortalClipping}.)
     */
    public static boolean shouldRenderEntityNow(Entity entity) {
        Validate.notNull(entity);
        if (IrisInterface.invoker.isRenderingShadowMap()) {
            return true;
        }
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            Portal collidingPortal = ((IEEntity) entity).ip_getCollidingPortal();

            if (entity instanceof Player && !renderingPortal.getDoRenderPlayer()) {
                return false;
            }

            // client colliding portal update is not immediate
            if (collidingPortal != null && !(entity instanceof LocalPlayer)) {
                if (!Portal.isReversePortal(collidingPortal, renderingPortal)) {
                    Vec3 cameraPos = CHelper.getCurrentCameraPos();

                    boolean isHidden = cameraPos.subtract(collidingPortal.getOriginPos())
                        .dot(collidingPortal.getNormal()) < 0;
                    if (isHidden) {
                        return false;
                    }
                }
            }

            return renderingPortal.isOnDestinationSide(
                getRenderingCameraPos(entity), -0.01
            );
        }
        return true;
    }

    public static Vec3 getRenderingCameraPos(Entity entity) {
        if (entity instanceof LocalPlayer) {
            return RenderStates.originalPlayerPos.add(
                McHelper.getEyeOffset(entity)
            );
        }
        return entity.getEyePosition(RenderStates.getPartialTick());
    }
}
