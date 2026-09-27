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
}
