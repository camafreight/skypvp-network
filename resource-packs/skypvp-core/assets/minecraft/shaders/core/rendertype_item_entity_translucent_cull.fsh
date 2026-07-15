#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 texCoord1;

out vec4 fragColor;

// Emissive plasma marker (alpha == 253): brightness-only animation so the hue stays
// texture RGB * tint — intended beam color for players, red for AI tracers. Black
// texels (the formerly transparent sections) render as opaque black and emit no light.
vec3 skypvp_plasma(vec3 baseRgb, vec2 uv, float gameTime) {
    float t = gameTime * 1200.0;
    float shimmer = 0.88
        + 0.22 * sin(t * 2.4 + (uv.x + uv.y) * 800.0)
        + 0.12 * sin(t * 3.7 + uv.y * 1300.0);
    return clamp(baseRgb * shimmer, 0.0, 1.6);
}

void main() {
    vec4 texColor = texture(Sampler0, texCoord0);
    int alphaByte = int(texColor.a * 255.0 + 0.5);
    bool plasma = alphaByte == 253;
    if (plasma) {
        texColor.rgb = skypvp_plasma(texColor.rgb, texCoord0, GameTime);
        texColor.a = 1.0;
    }

    vec4 color = texColor * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    if (plasma) {
        color.rgb = min(color.rgb * 1.25, vec3(1.6));
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
