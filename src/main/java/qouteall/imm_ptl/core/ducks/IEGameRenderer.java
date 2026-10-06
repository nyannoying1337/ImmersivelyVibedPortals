package qouteall.imm_ptl.core.ducks;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jetbrains.annotations.Nullable;

public interface IEGameRenderer {
    Lightmap ip_getLightmap();

    void ip_setLightmap(Lightmap lightmap);

    LightmapRenderStateExtractor ip_getLightmapRenderStateExtractor();

    FogRenderer ip_getFogRenderer();

    void ip_setFogRenderer(FogRenderer fogRenderer);

    void ip_setCamera(Camera camera);

    GlobalSettingsUniform ip_getGlobalSettingsUniform();

    /**
     * While not null, {@code GameRenderer.mainRenderTarget()} returns this target,
     * so that the LevelRenderer renders a portal view into it.
     */
    void ip_setMainRenderTargetOverride(@Nullable RenderTarget target);
    
    @Nullable RenderTarget ip_getMainRenderTargetOverride();

    void ip_extractCamera(DeltaTracker deltaTracker, float worldPartialTicks);

    /**
     * Apply the damage tilt and view bobbing (if enabled) of the camera state, like GameRenderer.renderLevel does
     * to the level projection.
     */
    void ip_applyViewBobbing(CameraRenderState cameraState, PoseStack poseStack);
}
