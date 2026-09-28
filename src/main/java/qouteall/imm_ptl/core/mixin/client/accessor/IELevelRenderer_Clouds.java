package qouteall.imm_ptl.core.mixin.client.accessor;

import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A CloudRenderer holds one cloud position per frame, so each portal view gets its own
 * (see PortalViewRenderer and docs/rendering-26.3.md).
 */
@Mixin(LevelRenderer.class)
public interface IELevelRenderer_Clouds {
    @Accessor("cloudRenderer")
    @Mutable
    void ip_setCloudRenderer(CloudRenderer cloudRenderer);
}
