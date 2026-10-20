#version 330
// Two dialects, picked per version: 26.3 compiles through SPIR-V and matches the values passed
// between stages by location; before it, OpenGL compiles this text as it is and binds by name.
// (Kept here rather than in the include, which isn't processed per version.)
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
#define LOC(n) layout(location = n)
#define VERTEX_INDEX gl_VertexIndex

#include <diorama:bake.glsl>
//?} else {
/*#define LOC(n)
#define VERTEX_INDEX gl_VertexID

#moj_import <diorama:bake.glsl>
*///?}

// Turns the double-size bake into the tile's pixels the way TileBaker.bakePixels does: plants
// and glass tinted over what is behind, water mixed over it by depth, the four samples of a pixel
// averaged, outlines where a neighbour is much nearer, then the style's grade and sRGB. Alpha is
// the share of known samples.
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D SceneAux;
uniform sampler2D Tint;
uniform sampler2D WaterColor;
uniform sampler2D WaterDepth;

layout(location = 0) out vec4 fragColor;
// The overlay bits of the pixel's first sample, as the tracer records them.
layout(location = 1) out vec4 fragInfo;

const float OUTLINE_DEPTH = 1.5;

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 mix_water(vec3 under, vec4 water, float depth) {
    vec3 w = water.rgb;
    float light = water.a;
    if (Grade.w > 0.5) {
        float peak = max(w.r, max(w.g, w.b));
        vec3 t = exp(-depth * 0.45 * (1.0 - w / peak) - depth * 0.04);
        vec3 deep = w * 0.55 * light;
        float sheen = 0.05 * light;
        return under * t + deep * (1.0 - t) + vec3(sheen, sheen, sheen * 1.2);
    }
    float a = clamp(1.0 - exp(-0.2 * depth), 0.5, 0.94);
    return under * (1.0 - a) + w * light * a;
}

// The average camera depth of a pixel's known samples, or -1 when none are known. Pixels are
// counted from the scene's one-pixel border, so the tile's own run from 1 to 512.
float pixel_depth(ivec2 pixel) {
    float sum = 0.0;
    int known = 0;
    for (int i = 0; i < 4; i++) {
        ivec2 s = pixel * 2 + ivec2(i & 1, i >> 1);
        if (texelFetch(SceneColor, s, 0).a > 0.5) {
            sum += bake_depth(texelFetch(SceneDepth, s, 0).r);
            known++;
        }
    }
    return known == 0 ? -1.0 : sum / float(known);
}

bool nearer(float neighbour, float here) {
    return neighbour >= 0.0 && here - neighbour > OUTLINE_DEPTH;
}

vec3 to_srgb(vec3 l) {
    l = clamp(l, 0.0, 1.0);
    return mix(l * 12.92, 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), l));
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy) + ivec2(1);
    fragInfo = vec4(texelFetch(SceneAux, pixel * 2, 0).r, 0.0, 0.0, 0.0);
    vec3 sum = vec3(0.0);
    int known = 0;
    for (int i = 0; i < 4; i++) {
        ivec2 s = pixel * 2 + ivec2(i & 1, i >> 1);
        vec4 scene = texelFetch(SceneColor, s, 0);
        if (scene.a < 0.5) {
            continue;
        }
        vec4 aux = texelFetch(SceneAux, s, 0);
        // A floor: the ray met nothing before the view's limit.
        bool nothing = aux.b > 0.5;
        float window = texelFetch(SceneDepth, s, 0).r;
        vec3 c = scene.rgb;
        if (!nothing) {
            vec4 tint = texelFetch(Tint, s, 0);
            if (tint.a < 1.0) {
                // The tracer lights the plants and glass it passed by the surface's own light.
                float light = luminance(c) / max(0.05, aux.g);
                c = c * tint.a + tint.rgb * light;
            }
        }
        float waterWindow = texelFetch(WaterDepth, s, 0).r;
        // Reversed depth: water in front of what was hit has the larger value.
        if (waterWindow > 0.0 && waterWindow >= window) {
            float surface = bake_depth(waterWindow);
            float depth;
            if (nothing || window <= 0.0) {
                // Water over nothing at all: the tracer gives it 64 blocks over black.
                c = vec3(0.0);
                depth = 64.0;
            } else {
                depth = max(0.0, bake_depth(window) - surface);
            }
            c = mix_water(c, texelFetch(WaterColor, s, 0), depth);
        }
        sum += c;
        known++;
    }
    if (known == 0) {
        fragColor = vec4(0.0);
        return;
    }
    vec3 c = sum / float(known);
    float saturation = Grade.x;
    float exposure = Grade.y;
    float l = luminance(c);
    c = (vec3(l) + (c - vec3(l)) * saturation) * exposure;
    vec3 srgb = to_srgb(c);
    if (Grade.z > 0.0) {
        float here = pixel_depth(pixel);
        if (nearer(pixel_depth(pixel + ivec2(-1, 0)), here) || nearer(pixel_depth(pixel + ivec2(1, 0)), here)
            || nearer(pixel_depth(pixel + ivec2(0, -1)), here) || nearer(pixel_depth(pixel + ivec2(0, 1)), here)) {
            srgb *= 1.0 - Grade.z;
        }
    }
    fragColor = vec4(srgb, floor(float(known) * 255.0 / 4.0) / 255.0);
}
