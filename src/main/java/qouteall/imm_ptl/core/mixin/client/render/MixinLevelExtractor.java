package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

@Mixin(LevelExtractor.class)
public class MixinLevelExtractor {
    // vanilla only notifies its current extractor, apply to the other dimensions too
    @Inject(method = "allChanged", at = @At("RETURN"))
    private void onAllChanged(CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().levelExtractor) {
            ClientWorldLoader._onCurrentExtractorAllChanged();
        }
    }
    
    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void onResourceReload(ResourceManager resourceManager, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().levelExtractor) {
            ClientWorldLoader._onResourceReload();
        }
    }
}
