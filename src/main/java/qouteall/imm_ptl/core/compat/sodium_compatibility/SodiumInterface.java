package qouteall.imm_ptl.core.compat.sodium_compatibility;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.render.FrustumCuller;

@Environment(EnvType.CLIENT)
public class SodiumInterface {
    
    @Nullable
    public static FrustumCuller frustumCuller = null;
    
    public static class Invoker {
        public boolean isSodiumPresent() {
            return false;
        }
        
        public Object createNewContext(int renderDistance) {
            return null;
        }
        
        public void switchContextWithCurrentWorldRenderer(Object context) {
        
        }
        
        public void markSpriteActive(TextureAtlasSprite sprite) {
        
        }
        
        public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {
        
        }
        
        /**
         * For the view rendered last with the current LevelRenderer: {visible sections with geometry, pending builds},
         * null without Sodium (see ViewDiagnostics).
         */
        public int @org.jetbrains.annotations.Nullable [] getViewSectionStats() {
            return null;
        }

        public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {
        
        }
    }
    
    public static Invoker invoker = new Invoker();
    
    
}
