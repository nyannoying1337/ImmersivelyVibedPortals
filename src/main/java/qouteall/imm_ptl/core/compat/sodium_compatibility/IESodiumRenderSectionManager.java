package qouteall.imm_ptl.core.compat.sodium_compatibility;

/**
 * Terrain diagnostics of the view rendered last (ViewDiagnostics). Implemented by MixinSodiumRenderSectionManager.
 */
public interface IESodiumRenderSectionManager {
    // visible sections that have geometry
    int ip_getSectionsWithGeometry();

    // pending tasks of the view (builds, rebuilds, resorts)
    int ip_getPendingBuilds();
}
