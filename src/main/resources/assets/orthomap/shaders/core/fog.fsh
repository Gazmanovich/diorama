#version 330
#extension GL_ARB_separate_shader_objects : require

// Volumetric-looking fog over the parts of the map with no data.
//
// The fog is a height field: deep in unknown territory it stands fogHeight blocks tall, and it
// thins out toward known ground, with noise breaking up the edge. We look at it through the same
// tilted camera as the map, so we march a few height layers from the top down. A layer at height h
// sits above the ground point h * cos(pitch) further south, which is what makes the banks look
// like they rise off the terrain.

layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

// Share of known ground per mask texel, in the red channel.
uniform sampler2D Sampler0;
// Tileable noise covering NOISE_PERIOD map units, a different scale per channel.
uniform sampler2D Sampler1;

layout(location = 0) in vec2 maskUv;
// rg: noise offset in eighths of a period; b: 1 for cave fog; a: mask texel size code.
layout(location = 1) in vec4 params;

layout(location = 0) out vec4 fragColor;

const float MASK_TEXELS = 256.0;
const float NOISE_PERIOD = 512.0;
const float COS_PITCH = 0.5;
const int LAYERS = 8;

// Surface fog banks are tall and soft. Cave fog hugs the ground with a tight edge, so a
// three-block tunnel you walked down stays readable instead of blurring into the fog.
float fogHeight;
float blurUnits;
float unitsPerUv;
vec2 noiseOrigin;
float seconds;

// Known share blurred over a radius in map units, so chunk-shaped edges melt away.
float knownBlur(vec2 uv, float units) {
    float r = max(units / unitsPerUv, 1.0 / MASK_TEXELS);
    float k = texture(Sampler0, uv).r * 0.2;
    k += texture(Sampler0, uv + vec2(r, 0.0)).r * 0.1;
    k += texture(Sampler0, uv - vec2(r, 0.0)).r * 0.1;
    k += texture(Sampler0, uv + vec2(0.0, r)).r * 0.1;
    k += texture(Sampler0, uv - vec2(0.0, r)).r * 0.1;
    float d = r * 1.9;
    k += texture(Sampler0, uv + vec2(d, d)).r * 0.1;
    k += texture(Sampler0, uv + vec2(-d, d)).r * 0.1;
    k += texture(Sampler0, uv + vec2(d, -d)).r * 0.1;
    k += texture(Sampler0, uv + vec2(-d, -d)).r * 0.1;
    return k;
}

// Surface fog blends a near and a wide blur, so banks reach well over the edge of known land
// and the noise has room to billow. Cave fog stays tight to the explored tunnels.
float knownSoft(vec2 uv) {
    if (blurUnits < 5.0) {
        return knownBlur(uv, blurUnits);
    }
    return 0.5 * knownBlur(uv, blurUnits) + 0.5 * knownBlur(uv, blurUnits * 2.4);
}

float noiseAt(vec2 uv) {
    vec2 n = noiseOrigin + uv * unitsPerUv / NOISE_PERIOD;
    // Speeds are whole periods per 1200 s, so the loop of GameTime wraps without a jump.
    float a = texture(Sampler1, n + vec2(5.0, 2.0) * seconds / 1200.0).r;
    float b = texture(Sampler1, n + vec2(-8.0, 3.0) * seconds / 1200.0).g;
    float c = texture(Sampler1, n + vec2(13.0, -9.0) * seconds / 1200.0).b;
    return a * 0.55 + b * 0.3 + c * 0.15;
}

float thicknessFrom(float known, float n) {
    float unknown = 1.0 - known;
    // Noise only shapes the edge; where everything is known there is no fog at all.
    float edge = smoothstep(0.0, 0.35, unknown);
    float amount = smoothstep(0.12, 0.75, unknown + (n - 0.5) * 1.5 * edge);
    return fogHeight * amount * (0.45 + 0.75 * n);
}

float thickness(vec2 uv) {
    return thicknessFrom(knownSoft(uv), noiseAt(uv));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    seconds = GameTime * 1200.0;
    noiseOrigin = floor(params.rg * 255.0 / 32.0 + 0.5) / 8.0;
    unitsPerUv = MASK_TEXELS * 2.0 * exp2(floor(params.a * 255.0 / 16.0 + 0.5));
    bool cave = params.b > 0.5;
    fogHeight = cave ? 4.0 : 10.0;
    blurUnits = cave ? 2.5 : 7.0;

    vec3 lit = cave ? vec3(0.24, 0.26, 0.33) : vec3(0.97, 0.97, 0.99);
    vec3 shade = cave ? vec3(0.035, 0.04, 0.055) : vec3(0.48, 0.55, 0.67);
    vec2 toSun = vec2(-1.0, -0.4) * (9.0 / unitsPerUv);

    vec3 color = vec3(0.0);
    float alpha = 0.0;
    float jitter = hash(gl_FragCoord.xy) - 0.5;
    for (int i = 0; i < LAYERS; i++) {
        float h = fogHeight * (1.0 - (float(i) + 0.5 + jitter * 0.6) / float(LAYERS));
        vec2 ground = maskUv + vec2(0.0, h * COS_PITCH / unitsPerUv);
        float th = thickness(ground);
        float a = smoothstep(h - 1.4, h + 1.4, th);
        if (a <= 0.002) {
            continue;
        }
        // More fog between this point and the sun means it sits in shadow.
        vec2 probe = ground + toSun;
        float toward = thicknessFrom(texture(Sampler0, probe).r, noiseAt(probe));
        float occlusion = clamp((toward - th) / fogHeight * 2.2, 0.0, 1.0);
        float light = clamp(0.2 + 0.7 * (h / fogHeight) + 0.3 - occlusion * 0.7, 0.0, 1.0);
        vec3 layerColor = mix(shade, lit, light);
        float layerAlpha = a * 0.45;
        color += (1.0 - alpha) * layerAlpha * layerColor;
        alpha += (1.0 - alpha) * layerAlpha;
        if (alpha > 0.985) {
            break;
        }
    }

    // The ground under a thick bank is never visible.
    float floorFog = smoothstep(0.5, 2.5, thickness(maskUv));
    color += (1.0 - alpha) * floorFog * shade;
    alpha += (1.0 - alpha) * floorFog;

    // Ground that is known right here stays visible; fog only rolls over its edges. Caves use the
    // sharp mask so narrow tunnels stay clear; the surface uses the blurred one so banks stay soft.
    float here = cave ? texture(Sampler0, maskUv).r : knownSoft(maskUv);
    alpha *= 1.0 - 0.85 * smoothstep(0.45, 0.95, here);

    // Where there is no map at all, nothing should show through, or the edge of the data would
    // read as a hard line inside the bank.
    // The cover takes the colour the layers already have here, so it doesn't show as a band.
    float empty = 1.0 - smoothstep(0.3, 0.85, texture(Sampler0, maskUv).r);
    vec3 coverColor = alpha > 0.02 ? color / alpha : mix(shade, lit, 0.7);
    color += (1.0 - alpha) * empty * coverColor;
    alpha += (1.0 - alpha) * empty;

    if (alpha <= 0.002) {
        discard;
    }
    color += (hash(gl_FragCoord.yx) - 0.5) / 255.0;
    fragColor = vec4(color / alpha, alpha) * ColorModulator;
}
