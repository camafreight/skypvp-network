#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;
in vec3 localPos;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    vec4 baseColor = tex * vertexColor * ColorModulator;

    // Distance from beam axis using UV.x (vanilla beam quads)
    float distFromCenter = abs(texCoord0.x - 0.5) * 2.0;
    float coreGlow = exp(-distFromCenter * 3.0);
    float wave = sin(localPos.y * 5.0 - GameTime * 1200.0) * 0.5 + 0.5;
    float outerGlow = exp(-distFromCenter * 1.2) * wave * 0.35;
    float finalGlow = clamp(coreGlow + outerGlow + 0.15, 0.0, 1.5);

    vec3 glowRGB = baseColor.rgb * (0.55 + finalGlow);
    // Soften boxy edges
    float edge = 1.0 - smoothstep(0.72, 1.0, distFromCenter);
    float finalAlpha = clamp(baseColor.a * (0.35 + finalGlow * 0.85) * edge, 0.0, 1.0);

    vec4 color = vec4(glowRGB, finalAlpha);
    float fragmentDistance = -ProjMat[3].z / ((gl_FragCoord.z) * -2.0 + 1.0 - ProjMat[2].z);
    fragColor = apply_fog(color, fragmentDistance, fragmentDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
