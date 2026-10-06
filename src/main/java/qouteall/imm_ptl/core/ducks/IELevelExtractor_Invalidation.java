package qouteall.imm_ptl.core.ducks;

/**
 * A LevelExtractor's "invalidate compiled geometry" request (set by allChanged) that was postponed to the start of
 * the next frame (MixinLevelExtractor.modifyShouldInvalidate). Implemented by MixinLevelExtractor.
 */
public interface IELevelExtractor_Invalidation {
    /**
     * @return whether a postponed request was pending (it is cleared)
     */
    boolean ip_takePostponedInvalidation();
}
