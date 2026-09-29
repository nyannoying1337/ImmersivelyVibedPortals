package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.jetbrains.annotations.Nullable;

/**
 * Only loaded when Iris is present.
 * <p>
 * {@link #isShaders()} is not overridden: its users are workarounds for the 1.21.1 renderer (mirror surface
 * offset, no portal overlays, lenient visibility test). In 26.3 portal views are rendered like the main view,
 * with the shaderpack's programs (clipped, see PortalClipping), so they don't apply.
 */
public class OnIrisPresent extends IrisInterface.Invoker {
    @Override
    public boolean isIrisPresent() {
        return true;
    }
    
    @Override
    public boolean isRenderingShadowMap() {
        return ShadowRenderer.ACTIVE;
    }
    
    @Nullable
    @Override
    public String getShaderpackName() {
        return Iris.getCurrentPack().isPresent() ? Iris.getCurrentPackName() : null;
    }
}
