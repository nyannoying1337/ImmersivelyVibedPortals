#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0;

layout(location = 0) in vec4 texProj0;
layout(location = 1) in vec2 clipDepth;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(textureProj(Sampler0, texProj0).rgb, 1.0);
    // depth clamp emulation, see portal_view.vsh
    gl_FragDepth = clamp(clipDepth.x / clipDepth.y, 0.0, 1.0);
}
