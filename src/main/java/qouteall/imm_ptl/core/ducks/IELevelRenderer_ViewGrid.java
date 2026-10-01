package qouteall.imm_ptl.core.ducks;

import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

/**
 * Moving a LevelRenderer's ViewArea grid for a portal view, before the view is extracted
 * (see DimensionRenderHelper.selectForView). Implemented by MixinLevelRenderer.
 */
public interface IELevelRenderer_ViewGrid {
    /**
     * @return whether the grid moved
     */
    boolean ip_moveGridForView(SectionPos center, Vec3 cameraPos);
}
