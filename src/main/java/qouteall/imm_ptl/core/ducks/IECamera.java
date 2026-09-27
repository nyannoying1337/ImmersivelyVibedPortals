package qouteall.imm_ptl.core.ducks;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;

public interface IECamera {
    void ip_resetState(Vec3 pos, ClientLevel currWorld);
    
    void portal_setPos(Vec3 pos);
    
    float ip_getCameraY();
    
    float ip_getLastCameraY();
    
    void ip_setCameraY(float cameraY, float lastCameraY);
    
    void portal_setFocusedEntity(Entity arg);
    
    /**
     * Make this camera see the world through a portal.
     * It copies the orientation, FOV and projection of the main camera,
     * places the camera at {@code pos} in {@code level}, and sets the view rotation matrix to
     * {@code mainViewRotation * extraTransformation} (the portal rotation/mirror/scale, may be null),
     * or to {@code extraTransformation} alone if {@code replaceRotation}.
     * The culling frustum is computed from that matrix.
     */
    void ip_setupAsPortalView(
        Camera mainCamera, ClientLevel level, Vec3 pos, @Nullable Matrix4fc extraTransformation,
        boolean replaceRotation
    );
}
