#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:fog.glsl>

// Draws a portal's surface. The content behind the portal was rendered into a target, which is sampled
// at this fragment's screen position. TextureMat maps that to the target's uv: the target covers only the
// portal's rectangle of the screen, and mirrored views are flipped (see PortalViewCrop).

layout(location = 0) in vec3 Position;

layout(location = 0) out vec4 texProj0;
layout(location = 1) out vec2 clipDepth;
layout(location = 2) out float sphericalVertexDistance;
layout(location = 3) out float cylindricalVertexDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texProj0 = TextureMat * projection_from_position(gl_Position);

    // Position is relative to the camera
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

    // Emulate depth clamp (not available in 26.3), like the original mod's GL_DEPTH_CLAMP:
    // the surface must not be clipped by the near or far plane, otherwise walking through the portal
    // shows the world behind it. Put z in the middle of the clip range (so only w > 0 still clips,
    // like the x/y planes do), and write the real depth per fragment instead.
    // Depth is reversed-Z and zero-to-one: window depth == z / w.
    clipDepth = gl_Position.zw;
    gl_Position.z = 0.5 * gl_Position.w;
}
