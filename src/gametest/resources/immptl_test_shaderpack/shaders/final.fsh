#version 120

// Test pack for the ImmPtl visual test: passes the scene through unchanged and draws a magenta border,
// so screenshots show that the pack is active.

uniform sampler2D colortex0;
uniform float viewWidth;
uniform float viewHeight;

varying vec2 texcoord;

void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
    vec2 pixel = texcoord * vec2(viewWidth, viewHeight);
    if (pixel.x < 6.0 || pixel.y < 6.0 || pixel.x > viewWidth - 6.0 || pixel.y > viewHeight - 6.0) {
        color = vec3(1.0, 0.0, 1.0);
    }
    gl_FragColor = vec4(color, 1.0);
}
