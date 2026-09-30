package qouteall.imm_ptl.core;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.Event;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;
import qouteall.imm_ptl.core.render.renderer.RendererDummy;
import qouteall.q_misc_util.Helper;

@Environment(EnvType.CLIENT)
public class IPCGlobal {
    
    // In 26.3 portal views are rendered by PortalViewRenderer. The PortalRenderer hooks are no-ops for now.
    // TODO(26.3): remove the PortalRenderer hooks once the remaining callers are ported
    public static RendererDummy rendererDummy = new RendererDummy();
    public static PortalRenderer renderer = rendererDummy;
    
    public static int maxIdleChunkRendererNum = 500;
    
    public static boolean doUseAdvancedFrustumCulling = true;
    public static boolean useHackedChunkRenderDispatcher = true;
    public static boolean isClientRemoteTickingEnabled = true;
    public static boolean useFrontClipping = true;
    public static boolean doDisableAlphaTestWhenRenderingFrameBuffer = true;
    public static boolean lateClientLightUpdate = true;
    public static boolean earlyRemoteUpload = true;
    
    public static boolean useSuperAdvancedFrustumCulling = true;
    public static boolean earlyFrustumCullingPortal = true;
    // skip the views of portals hidden behind blocks (PortalOcclusionCulling)
    public static boolean cullHiddenPortals = true;
    
    public static boolean useSeparatedStencilFormat = false;
    
    public static boolean experimentalIrisPortalRenderer = false;
    
    public static boolean debugEnableStencilWithIris = false;
    
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
