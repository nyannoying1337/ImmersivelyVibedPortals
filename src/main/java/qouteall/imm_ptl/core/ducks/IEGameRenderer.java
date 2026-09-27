package qouteall.imm_ptl.core.ducks;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.fog.FogRenderer;
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
}
