package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

@Mixin(Camera.class)
public abstract class MixinCamera implements IECamera {
    @Shadow
    private Vec3 position;
    @Shadow
    private @Nullable Level level;
    @Shadow
    private @Nullable Entity entity;
    @Shadow
    private float eyeHeight;
    @Shadow
    private float eyeHeightOld;
    @Shadow
    private boolean initialized;
    @Shadow
    private boolean detached;
    @Shadow
    private float xRot;
    @Shadow
    private float yRot;
    @Shadow
    @Final
    private Quaternionf rotation;
    @Shadow
    @Final
    private Vector3f forwards;
    @Shadow
    @Final
    private Vector3f panoramicForwards;
    @Shadow
    @Final
    private Vector3f up;
    @Shadow
    @Final
    private Vector3f left;
    @Shadow
    private Frustum cullFrustum;
    @Shadow
    @Final
    private Matrix4f cachedViewRotMatrix;
    @Shadow
    private int matrixPropertiesDirty;
    @Shadow
    private float fov;
    @Shadow
    private float hudFov;
    @Shadow
    private float depthFar;
    
    @Shadow
    protected abstract void setPosition(Vec3 vec3d_1);
    
    @Shadow
    public abstract Matrix4f getViewRotationMatrix(Matrix4f dest);
    
    @Shadow
    protected abstract Matrix4f createProjectionMatrixForCulling();
    
    @Shadow
    protected abstract void setupPerspective(float zNear, float zFar, float fov, float width, float height);
    
    /**
     * After teleporting through a portal with rotation, the view rotation is interpolated
     * from the old orientation (see {@link TransformationManager}).
     * Applied to the cached view rotation matrix, so the extracted camera state and the culling frustum use it.
     */
    @Inject(method = "update", at = @At("RETURN"))
    private void onUpdateFinished(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!TransformationManager.isAnimationRunning()) {
            return;
        }
        if ((Object) this != Minecraft.getInstance().gameRenderer.mainCamera()) {
            return;
        }
        Matrix4f viewRotation = getViewRotationMatrix(new Matrix4f());
        TransformationManager.applyAnimationDelta(viewRotation);
        this.cachedViewRotMatrix.set(viewRotation);
        this.matrixPropertiesDirty = (this.matrixPropertiesDirty & ~1) | 2;
        this.cullFrustum = new Frustum(viewRotation, createProjectionMatrixForCulling());
        this.cullFrustum.prepare(position.x, position.y, position.z);
    }

    @Inject(method = "alignWithEntity", at = @At("RETURN"))
    private void onAlignWithEntityFinished(float partialTicks, CallbackInfo ci) {
        Camera this_ = (Camera) (Object) this;
        WorldRenderInfo.adjustCameraPos(this_);
    }
    
    @Inject(
        method = "getFluidInCamera",
        at = @At("HEAD"),
        cancellable = true
    )
    private void getSubmergedFluidState(CallbackInfoReturnable<FogType> cir) {
        if (PortalRendering.isRendering()) {
            cir.setReturnValue(FogType.NONE);
        }
    }
    
    // to let the player be rendered when rendering portal
    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void onIsThirdPerson(CallbackInfoReturnable<Boolean> cir) {
        if (CrossPortalEntityRenderer.shouldRenderPlayerDefault()) {
            cir.setReturnValue(true);
        }
    }
    
    @Override
    public void ip_resetState(Vec3 pos, ClientLevel currWorld) {
        setPosition(pos);
        level = currWorld;
    }
    
    @Override
    public float ip_getCameraY() {
        return eyeHeight;
    }
    
    @Override
    public float ip_getLastCameraY() {
        return eyeHeightOld;
    }
    
    @Override
    public void ip_setCameraY(float cameraY_, float lastCameraY_) {
        eyeHeight = cameraY_;
        eyeHeightOld = lastCameraY_;
    }
    
    @Override
    public void portal_setPos(Vec3 pos) {
        setPosition(pos);
    }
    
    @Override
    public void portal_setFocusedEntity(Entity arg) {
        entity = arg;
    }
    
    @Override
    public void ip_setupAsPortalView(
        Camera mainCamera, ClientLevel newLevel, Vec3 pos, @Nullable Matrix4fc extraTransformation,
        boolean replaceRotation
    ) {
        MixinCamera main = (MixinCamera) (Object) mainCamera;
        
        this.level = newLevel;
        this.entity = main.entity;
        this.initialized = main.initialized;
        this.detached = main.detached;
        this.eyeHeight = main.eyeHeight;
        this.eyeHeightOld = main.eyeHeightOld;
        this.xRot = main.xRot;
        this.yRot = main.yRot;
        this.rotation.set(main.rotation);
        this.forwards.set(main.forwards);
        this.panoramicForwards.set(main.panoramicForwards);
        this.up.set(main.up);
        this.left.set(main.left);
        this.fov = main.fov;
        this.hudFov = main.hudFov;
        this.depthFar = main.depthFar;
        setPosition(pos);
        
        Minecraft client = Minecraft.getInstance();
        setupPerspective(
            0.05F, depthFar, fov,
            client.getWindow().getWidth(), client.getWindow().getHeight()
        );
        
        // view rotation = main view rotation * portal transformation
        Matrix4f viewRotation;
        if (replaceRotation) {
            viewRotation = extraTransformation == null ? new Matrix4f() : new Matrix4f(extraTransformation);
        }
        else {
            viewRotation = mainCamera.getViewRotationMatrix(new Matrix4f());
            if (extraTransformation != null) {
                viewRotation.mul(extraTransformation);
            }
        }
        this.cachedViewRotMatrix.set(viewRotation);
        // mark the view rotation matrix as up to date (so it's not recomputed from the quaternion)
        // and the cached view-rotation-projection matrix as dirty
        this.matrixPropertiesDirty = (this.matrixPropertiesDirty & ~1) | 2;
        
        this.cullFrustum = new Frustum(viewRotation, createProjectionMatrixForCulling());
        this.cullFrustum.prepare(pos.x, pos.y, pos.z);
    }
}
