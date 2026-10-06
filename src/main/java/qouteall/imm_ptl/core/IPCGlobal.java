package qouteall.imm_ptl.core;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.Event;
import qouteall.q_misc_util.Helper;

@Environment(EnvType.CLIENT)
public class IPCGlobal {
    
    public static boolean doUseAdvancedFrustumCulling = true;
    public static boolean isClientRemoteTickingEnabled = true;
    public static boolean lateClientLightUpdate = true;
    public static boolean earlyRemoteUpload = true;
    
    public static boolean useSuperAdvancedFrustumCulling = true;
    public static boolean earlyFrustumCullingPortal = true;
    // skip the views of portals hidden behind blocks (PortalOcclusionCulling)
    public static boolean cullHiddenPortals = true;
    // render portal views only for the screen rectangle their portal covers (PortalViewCrop)
    public static boolean cropPortalViews = true;

    /**
     * Fired when client exits world or doing conventional dimension travel (with loading screen).
     */
    public static final Event<Runnable> CLIENT_CLEANUP_EVENT =
        Helper.createRunnableEvent();
    
    /**
     * Fired when client exits world. Does not fire when doing conventional dimension travel.
     */
    public static final Event<Runnable> CLIENT_EXIT_EVENT =
        Helper.createRunnableEvent();
}
