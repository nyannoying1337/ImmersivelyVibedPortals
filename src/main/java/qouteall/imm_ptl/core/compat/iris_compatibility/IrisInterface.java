package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.Helper;

import java.lang.reflect.Field;

public class IrisInterface {
    
    public static class Invoker {
        public boolean isIrisPresent() {
            return false;
        }
        
        public boolean isShaders() {
            return false;
        }
        
        public boolean isRenderingShadowMap() {
            return false;
        }
        
        public Object getPipeline(LevelRenderer worldRenderer) {
            return null;
        }
        
        public void setPipeline(LevelRenderer worldRenderer, Object pipeline) {
        
        }
        
        public void reloadPipelines() {}
    
        @Nullable
        public String getShaderpackName() {
            return null;
        }
    }
    
    
    public static Invoker invoker = new Invoker();
}
