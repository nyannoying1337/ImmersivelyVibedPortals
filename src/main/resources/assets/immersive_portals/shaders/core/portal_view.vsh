#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:dynamictransforms.glsl>

// Draws a portal's surface. The content behind the portal was rendered into a
// screen-sized target, which is sampled at this fragment's screen position.

layout(location = 0) in vec3 Position;

layout(location = 0) out vec4 texProj0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texProj0 = projection_from_position(gl_Position);

    // Emulate depth clamp (not available in 26.3): when the camera is closer to the portal
    // than the near plane (e.g. while walking through it), the surface must not be clipped,
    // otherwise the world behind the portal shows through for those frames.
    // Depth is reversed-Z, so the near plane is at z == w; keep z <= w.
    gl_Position.z = min(gl_Position.z, gl_Position.w);
}
