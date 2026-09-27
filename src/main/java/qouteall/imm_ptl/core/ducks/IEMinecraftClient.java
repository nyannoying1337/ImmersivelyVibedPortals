package qouteall.imm_ptl.core.ducks;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;

public interface IEMinecraftClient {
    // In 26.3 a dimension's rendering is split into a LevelExtractor and a LevelRenderer.
    // They are always switched together.
    void ip_setLevelRendererAndExtractor(LevelRenderer levelRenderer, LevelExtractor levelExtractor);
    
    Thread ip_getRunningThread();
}
