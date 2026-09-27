package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.Helper;
import java.lang.reflect.Field;

// Only loaded when the mod is present. Excluded from the build until its compat is ported to 26.3.
public class OnIrisPresent extends IrisInterface.Invoker {
    
    private Field worldRendererPipelineField = Helper.noError(() -> {
        Field field = LevelRenderer.class.getDeclaredField("pipeline");
        field.setAccessible(true);
        return field;
    });
    
    @Override
    public boolean isIrisPresent() {
        return true;
    }
    
    @Override
    public boolean isShaders() {
        return Iris.getCurrentPack().isPresent();
    }
    
    @Override
    public boolean isRenderingShadowMap() {
        return ShadowRenderer.ACTIVE;
    }
    
    @Override
    public Object getPipeline(LevelRenderer worldRenderer) {
        return Helper.noError(() ->
            ((WorldRenderingPipeline) worldRendererPipelineField.get(worldRenderer))
        );
    }
    
    // the pipeline switching is unnecessary when using shaders
    // but still necessary with shaders disabled
    @Override
    public void setPipeline(LevelRenderer worldRenderer, Object pipeline) {
        Helper.noError(() -> {
            worldRendererPipelineField.set(worldRenderer, pipeline);
            return null;
        });
    }
    
    @Override
    public void reloadPipelines() {
        Iris.getPipelineManager().destroyPipeline();
    }

    @Nullable
    @Override
    public String getShaderpackName() {
        return Iris.getCurrentPackName();
    }
}
