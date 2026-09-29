#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec4 texProj0;
layout(location = 1) in vec2 clipDepth;
layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 texCoord = texProj0;
#ifdef IMMPTL_FLIP_X
    // the view was rendered with a horizontally flipped projection (mirror), see PortalSurfaceRendering
    texCoord.x = texCoord.w - texCoord.x;
#endif
    // Fog like the terrain around the portal: the content behind the portal is at least as far away as the
    // surface. Beyond the render distance the surface is left out, so the sky shows there like elsewhere
    // (e.g. the world-sized floor portal of a dimension stack seen at the horizon).
    float renderDistanceFog = linear_fog_value(cylindricalVertexDistance, FogRenderDistanceStart, FogRenderDistanceEnd);
    if (renderDistanceFog >= 1.0) {
        discard;
    }
    float environmentalFog = linear_fog_value(sphericalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd);
    vec3 color = textureProj(Sampler0, texCoord).rgb;
    color = mix(color, FogColor.rgb, max(environmentalFog, renderDistanceFog) * FogColor.a);
    fragColor = vec4(color, 1.0);
    // depth clamp emulation, see portal_view.vsh
    gl_FragDepth = clamp(clipDepth.x / clipDepth.y, 0.0, 1.0);
}
