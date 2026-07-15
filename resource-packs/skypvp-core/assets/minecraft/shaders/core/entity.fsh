#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
#else
in vec4 vertexColor;
#endif
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

// Emissive plasma: texels marked with alpha == 253 (laser bolt pulses, carbine vents).
// Brightness-only animation — hue stays texture RGB * tint, so every beam keeps its
// intended color (config dye for player shots, red for AI tracers). The texture's
// animation frames carry the traveling pulses; black texels (RGB 0) stay black, which
// keeps light on the animated pulses only.
vec3 skypvp_plasma(vec3 baseRgb, vec2 uv, float gameTime) {
    float t = gameTime * 1200.0;
    float shimmer = 0.88
        + 0.22 * sin(t * 2.4 + (uv.x + uv.y) * 800.0)
        + 0.12 * sin(t * 3.7 + uv.y * 1300.0);
    return clamp(baseRgb * shimmer, 0.0, 1.6);
}

void main() {
    vec4 texColor = texture(Sampler0, texCoord0);
    // Detect marker before lighting/modulation so vertex alpha cannot hide it.
    int alphaByte = int(texColor.a * 255.0 + 0.5);
    bool plasma = alphaByte == 253;
    if (plasma) {
        texColor.rgb = skypvp_plasma(texColor.rgb, texCoord0, GameTime);
        texColor.a = 1.0;
    }

    vec4 color = texColor;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
#ifdef PER_FACE_LIGHTING
    color *= (gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack) * ColorModulator;
#else
    color *= vertexColor * ColorModulator;
#endif
#ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#endif
#ifndef EMISSIVE
    if (plasma) {
        // Full-bright, no lightmap: the animated pulses are the only light source.
        // Black gap/edge texels are RGB 0, so they emit nothing regardless.
        color.rgb = min(color.rgb * 1.25, vec3(1.6));
    } else {
        color *= lightMapColor;
    }
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
