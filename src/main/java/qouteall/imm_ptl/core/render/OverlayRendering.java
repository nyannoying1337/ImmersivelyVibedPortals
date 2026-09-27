package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the block overlay of breakable portals (e.g. the nether portal texture on a nether portal).
 * Submitted by {@link PortalEntityRenderer}.
 */
@Environment(EnvType.CLIENT)
public class OverlayRendering {
    private static final RandomSource random = RandomSource.create();

    // light: block 15, sky 14
    private static final int OVERLAY_LIGHT_COORDS = 14680304;

    public static boolean shouldRenderOverlay(Portal portal) {
        if (portal instanceof BreakablePortalEntity breakablePortalEntity) {
            if (breakablePortalEntity.getActualOverlay() != null) {
                return breakablePortalEntity.isInFrontOfPortal(CHelper.getCurrentCameraPos());
            }
        }
        return false;
    }

    private static boolean shaderOverlayWarned = false;

    /**
     * The pose stack must be at the portal's origin (relative to the camera).
     */
    public static void submitOverlay(
        Portal portal,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector
    ) {
        if (IrisInterface.invoker.isShaders()) {
            if (!shaderOverlayWarned) {
                shaderOverlayWarned = true;
                CHelper.printChat("[Immersive Portals] Portal overlay cannot be rendered with shaders");
            }

            return;
        }

        if (portal instanceof BreakablePortalEntity breakablePortalEntity) {
            submitBreakablePortalOverlay(breakablePortalEntity, poseStack, submitNodeCollector);
        }
    }

    public static List<BakedQuad> getQuads(BlockStateModel model, Vec3 portalNormal) {
        Direction facing = Direction.getApproximateNearest(portalNormal);

        List<BlockStateModelPart> parts = new ArrayList<>();
        random.setSeed(0);
        model.collectParts(random, parts);

        List<BakedQuad> result = new ArrayList<>();

        for (BlockStateModelPart part : parts) {
            result.addAll(part.getQuads(facing));
            result.addAll(part.getQuads(null));
        }

        if (result.isEmpty()) {
            for (BlockStateModelPart part : parts) {
                for (Direction direction : Direction.values()) {
                    result.addAll(part.getQuads(direction));
                }
            }
        }

        return result;
    }

    /**
     * {@link net.minecraft.client.renderer.entity.FallingBlockRenderer}
     */
    private static void submitBreakablePortalOverlay(
        BreakablePortalEntity portal,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector
    ) {
        BreakablePortalEntity.OverlayInfo overlay = portal.getActualOverlay();
        if (overlay == null) {
            return;
        }

        BlockState blockState = overlay.blockState();
        if (blockState == null) {
            return;
        }

        BlockPortalShape blockPortalShape = portal.blockPortalShape;
        if (blockPortalShape == null) {
            return;
        }

        BlockStateModel model = Minecraft.getInstance().getModelManager()
            .getBlockStateModelSet().get(blockState);
        List<BakedQuad> quads = getQuads(model, portal.getNormal());
        if (quads.isEmpty()) {
            return;
        }

        for (BakedQuad quad : quads) {
            SodiumInterface.invoker.markSpriteActive(quad.materialInfo().sprite());
        }

        QuadInstance quadInstance = new QuadInstance();
        quadInstance.setColor(ARGB.colorFromFloat((float) overlay.opacity(), 1.0F, 1.0F, 1.0F));
        quadInstance.setLightCoords(OVERLAY_LIGHT_COORDS);

        Vec3 offset = portal.getNormal().scale(overlay.offset());
        Vec3 pos = portal.position();
        List<BlockPos> area = new ArrayList<>(blockPortalShape.area);

        RenderType renderType = RenderTypes.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS);

        submitNodeCollector.submitCustomGeometry(
            poseStack, renderType,
            (pose, buffer) -> {
                PoseStack blockPoseStack = new PoseStack();
                blockPoseStack.last().set(pose);
                blockPoseStack.translate(offset.x, offset.y, offset.z);

                for (BlockPos blockPos : area) {
                    blockPoseStack.pushPose();
                    blockPoseStack.translate(
                        blockPos.getX() - pos.x, blockPos.getY() - pos.y, blockPos.getZ() - pos.z
                    );

                    if (overlay.rotation() != null) {
                        blockPoseStack.rotate(overlay.rotation().toMcQuaternion());
                    }

                    putQuads(buffer, blockPoseStack.last(), quads, quadInstance);

                    blockPoseStack.popPose();
                }
            }
        );
    }

    private static void putQuads(
        VertexConsumer buffer, PoseStack.Pose pose, List<BakedQuad> quads, QuadInstance quadInstance
    ) {
        for (BakedQuad quad : quads) {
            buffer.putBakedQuad(pose, quad, quadInstance);
        }
    }
}
