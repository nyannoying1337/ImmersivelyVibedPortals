package qouteall.imm_ptl.core.platform_specific;

/**
 * Presets of the settings that cost the most performance (IPConfig.preset).
 * Choosing one in the config screen sets these settings when the config is saved; changing one of them by hand
 * afterwards makes the preset "custom" (IPConfig.onConfigChanged).
 * indirectLoadingRadiusCap is a server setting: in singleplayer the preset sets it, on a server the server's config
 * decides.
 * (Not SelectionListEntry.Translatable for nicer names: that is a client class, and the config is also loaded on
 * dedicated servers.)
 */
public enum ConfigPreset {
    custom(-1, false, -1, false, false, false),
    performance(2, true, 4, false, true, true),
    balanced(5, false, 8, true, true, true),
    quality(5, false, 16, true, false, false);

    // portal-in-portal rendering depth
    private final int maxPortalLayer;
    // portal views rendered at a third of the render distance
    private final boolean reducedPortalRendering;
    // chunks loaded behind portals the player isn't standing at
    private final int indirectLoadingRadiusCap;
    private final boolean renderYourselfInPortal;
    // less terrain loaded and rendered behind portals at low FPS
    private final boolean enableClientPerformanceAdjustment;
    // far, small portals re-rendered every 2nd/3rd frame while nothing moves (FarPortalViewReuse)
    private final boolean reduceFarPortalUpdates;

    ConfigPreset(
        int maxPortalLayer, boolean reducedPortalRendering, int indirectLoadingRadiusCap,
        boolean renderYourselfInPortal, boolean enableClientPerformanceAdjustment, boolean reduceFarPortalUpdates
    ) {
        this.maxPortalLayer = maxPortalLayer;
        this.reducedPortalRendering = reducedPortalRendering;
        this.indirectLoadingRadiusCap = indirectLoadingRadiusCap;
        this.renderYourselfInPortal = renderYourselfInPortal;
        this.enableClientPerformanceAdjustment = enableClientPerformanceAdjustment;
        this.reduceFarPortalUpdates = reduceFarPortalUpdates;
    }

    public void applyTo(IPConfig config) {
        if (this == custom) {
            return;
        }
        config.maxPortalLayer = maxPortalLayer;
        config.reducedPortalRendering = reducedPortalRendering;
        config.indirectLoadingRadiusCap = indirectLoadingRadiusCap;
        config.renderYourselfInPortal = renderYourselfInPortal;
        config.enableClientPerformanceAdjustment = enableClientPerformanceAdjustment;
        config.reduceFarPortalUpdates = reduceFarPortalUpdates;
    }

    public boolean matches(IPConfig config) {
        return this != custom
            && config.maxPortalLayer == maxPortalLayer
            && config.reducedPortalRendering == reducedPortalRendering
            && config.indirectLoadingRadiusCap == indirectLoadingRadiusCap
            && config.renderYourselfInPortal == renderYourselfInPortal
            && config.enableClientPerformanceAdjustment == enableClientPerformanceAdjustment
            && config.reduceFarPortalUpdates == reduceFarPortalUpdates;
    }
}
