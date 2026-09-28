#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0;

layout(location = 0) in vec4 texProj0;
layout(location = 1) in vec2 clipDepth;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 texCoord = texProj0;
#ifdef IMMPTL_FLIP_X
    // the view was rendered with a horizontally flipped projection (mirror), see PortalSurfaceRendering
    texCoord.x = texCoord.w - texCoord.x;
#endif
    fragColor = vec4(textureProj(Sampler0, texCoord).rgb, 1.0);
    // depth clamp emulation, see portal_view.vsh
    gl_FragDepth = clamp(clipDepth.x / clipDepth.y, 0.0, 1.0);
}
