package qouteall.imm_ptl.core.render.renderer;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.q_misc_util.Helper;

import java.util.function.Predicate;
import java.util.function.Supplier;

public abstract class PortalRenderer {
    
    /**
     * An event for filtering whether a portal should render.
     * All listeners' results are ANDed.
     */
    public static final Event<Predicate<Portal>> PORTAL_RENDERING_PREDICATE =
        EventFactory.createArrayBacked(
            Predicate.class,
            (listeners) -> (portal) -> {
                for (Predicate<Portal> listener : listeners) {
                    if (!listener.test(portal)) {
                        return false;
                    }
                }
                return true;
            }
        );
    
    public static final Minecraft client = Minecraft.getInstance();

    // In 1.21.1 the renderer subclasses (stencil, Iris compat...) were driven by instance hooks
    // (translucent/hand rendering, framebuffer clearing, portal entity rendering).
    // In 26.3 portal views are rendered by PortalViewRenderer and portal surfaces by PortalEntityRenderer,
    // so those hooks are gone (see docs/rendering-26.3.md).
    // TODO(26.3): Iris support. The renderer switching (IPCGlobal.renderer, switchToCorrectRenderer, which also ran
    //  IPModInfoChecking.checkShaderpack) was removed with the hooks; redo it when an Iris-compatible renderer exists.

    public static boolean shouldSkipRenderingPortal(Portal portal, Supplier<Frustum> frustumSupplier) {
        if (!portal.isPortalValid()) {
            return true;
        }
        
        // if max portal layer is 0, the invisible portals will be force rendered
        if (!portal.isVisible() && IPGlobal.maxPortalLayer != 0) {
            return true;
        }
        
        if (RenderStates.getRenderedPortalNum() >= IPGlobal.portalRenderLimit) {
            return true;
        }
        
        Vec3 cameraPos = TransformationManager.getIsometricAdjustedCameraPos();
        
        if (!portal.isRoughlyVisibleTo(cameraPos)) {
            return true;
        }
        
        if (PortalRendering.isRendering()) {
            Portal outerPortal = PortalRendering.getRenderingPortal();
            
            if (outerPortal.cannotRenderInMe(portal)) {
                return true;
            }
        }
        
        double distance = portal.getDistanceToNearestPointInPortal(cameraPos);
        if (distance > getRenderRange()) {
            return true;
        }
        
        if (IPCGlobal.earlyFrustumCullingPortal) {
            // frustum culling does not work when portal is very close
            if (distance > 0.1) {
                Frustum frustum = frustumSupplier.get();
                if (!frustum.isVisible(portal.getThinBoundingBox())) {
                    return true;
                }
            }
        }
        
        if (PortalRendering.isInvalidRecursionRendering(portal)) {
            return true;
        }
        
        boolean predicateTest = PORTAL_RENDERING_PREDICATE.invoker().test(portal);
        if (!predicateTest) {
            return true;
        }
        
        return false;
    }
    
    public static double getRenderRange() {
        double range = client.options.getEffectiveRenderDistance() * 16;
        if (RenderStates.isLaggy || IPGlobal.reducedPortalRendering) {
            range = 16;
        }
        if (PortalRendering.getPortalLayer() > 1) {
            //do not render deep layers of mirror when far away
            range /= (PortalRendering.getPortalLayer());
        }
        if (PortalRendering.getPortalLayer() >= 1) {
            double outerPortalScale = PortalRendering.getRenderingPortal().getScale();
            if (outerPortalScale > 2) {
                range *= outerPortalScale;
                range = Math.min(range, 32 * 16);
            }
        }
        return range;
    }
    
    public static int getPortalRenderDistance(Portal portal) {
        int mcRenderDistance = client.options.getEffectiveRenderDistance();
        
        if (portal.getScale() > 2) {
            double radiusBlocks = portal.getDestAreaRadiusEstimation() * 1.4;
            
            radiusBlocks = Math.min(radiusBlocks, 32 * 16);
            
            return Math.max((int) (radiusBlocks / 16), mcRenderDistance);
        }
        if (IPGlobal.reducedPortalRendering) {
            return mcRenderDistance / 3;
        }
        return mcRenderDistance;
    }
    
    @Nullable
    public static Matrix4f getPortalTransformation(Portal portal) {
        Matrix4f rot = getPortalRotationMatrix(portal);
        
        Matrix4f mirror = portal instanceof Mirror ?
            TransformationManager.getMirrorTransformation(portal.getNormal()) : null;
        
        Matrix4f scale = getPortalScaleMatrix(portal);
        
        return combineNullable(rot, combineNullable(mirror, scale));
    }
    
    @Nullable
    public static Matrix4f getPortalRotationMatrix(Portal portal) {
        if (portal.getRotation() == null) {
            return null;
        }
        
        Quaternionf rot = portal.getRotation().toMcQuaternion();
        rot.conjugate();
        return rot.get(new Matrix4f());
    }
    
    @Nullable
    public static Matrix4f combineNullable(@Nullable Matrix4f a, @Nullable Matrix4f b) {
        return Helper.combineNullable(a, b, (m1, m2) -> {
            m1.mul(m2);
            return m1;
        });
    }
    
    @Nullable
    public static Matrix4f getPortalScaleMatrix(Portal portal) {
        // if it's not a fuseView portal
        // whether to apply scale transformation to camera does not change triangle position
        // to avoid abrupt fog change, do not apply for non-fuse-view portal
        // for fuse-view portal, the depth value should be correct so the scale should be applied
        if (shouldApplyScaleToModelView(portal)) {
            float v = (float) (1.0 / portal.getScale());
            return new Matrix4f().scale(v, v, v);
        }
        return null;
    }
    
    public static boolean shouldApplyScaleToModelView(Portal portal) {
        return portal.hasScaling() && portal.isFuseView();
    }
}
