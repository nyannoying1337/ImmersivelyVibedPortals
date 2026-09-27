package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Window-sized color+depth targets that portal views are rendered into.
 * Targets are handed out in order during a frame and all become free again at the next frame.
 */
public class PortalViewTargetPool {
    private final List<TextureTarget> targets = new ArrayList<>();
    private int used = 0;

    public void beginFrame() {
        used = 0;
    }

    /**
     * @return a target sized to the window, or null if the limit is reached
     */
    public @Nullable TextureTarget acquire(int limit) {
        if (used >= limit) {
            return null;
        }

        int width = Minecraft.getInstance().getWindow().getWidth();
        int height = Minecraft.getInstance().getWindow().getHeight();

        TextureTarget target;
        if (used < targets.size()) {
            target = targets.get(used);
            if (target.width != width || target.height != height) {
                target.resize(width, height);
            }
        }
        else {
            target = new TextureTarget(
                "ImmPtl Portal View " + used, width, height,
                GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT
            );
            targets.add(target);
            PortalSurfaceRendering.onTargetCreated(targets.size() - 1, target);
        }
        used++;
        return target;
    }

    public int indexOf(TextureTarget target) {
        return targets.indexOf(target);
    }
    
    public void cleanUp() {
        PortalSurfaceRendering.onPoolCleared();
        for (TextureTarget target : targets) {
            target.destroyBuffers();
        }
        targets.clear();
        used = 0;
    }
}
