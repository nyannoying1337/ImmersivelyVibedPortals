package qouteall.imm_ptl.core.compat.sodium_compatibility;

import org.joml.Vector4f;

/**
 * Implemented by Sodium's terrain uniforms record (UniformBufferManager.GlobalUniforms) via mixin.
 */
public interface IESodiumGlobalUniforms {
    Vector4f ip_getClipPlane();
}
