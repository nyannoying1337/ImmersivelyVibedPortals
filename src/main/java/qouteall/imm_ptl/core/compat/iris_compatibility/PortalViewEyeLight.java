package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.render.PortalViewRenderer;

/**
 * Iris measures the light at the eyes (eyeBrightness, used by packs for cave fog and exposure) at the camera entity's
 * position in the level being rendered. In a portal view that is the destination level, but the player is in another
 * place (or dimension): e.g. the overworld at the nether coordinates of the player, usually underground, so a view
 * of a sunny landscape got thick cave fog. During portal views it's measured at the view camera instead
 * (MixinIrisCommonUniforms, MixinIrisHardcodedCustomUniforms).
 */
public class PortalViewEyeLight {
    /**
     * @return {block light, sky light} (0..15) at the portal view camera, or null if no portal view is rendered
     */
    public static int @Nullable [] get() {
        if (!PortalViewRenderer.isRenderingPortalView()) {
            return null;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return null;
        }
        BlockPos eyePos = BlockPos.containing(client.gameRenderer.mainCamera().position());
        return new int[]{
            client.level.getBrightness(LightLayer.BLOCK, eyePos),
            client.level.getBrightness(LightLayer.SKY, eyePos)
        };
    }
}
