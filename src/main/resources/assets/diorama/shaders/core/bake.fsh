#version 330
#extension GL_ARB_separate_shader_objects : require

#include <diorama:bake.glsl>

// The same lighting as TileBaker.shade for the sky view, per fragment. One fragment of the
// double-size target is one of the tracer's four samples per pixel.
#ifndef SHADOW
uniform sampler2D ShadowMap;

layout(location = 0) in vec3 localPos;
layout(location = 1) in vec2 faceUv;
layout(location = 2) flat in vec4 faceColor;
layout(location = 3) flat in vec4 faceOcclusion;
layout(location = 4) flat in vec4 faceSky;
layout(location = 5) flat in ivec2 faceInfo;

layout(location = 0) out vec4 fragColor;

const int FLAG_UNKNOWN = 1;

float bilinear(vec4 c, vec2 f) {
    float top = mix(c.x, c.y, f.x);
    float bottom = mix(c.z, c.w, f.x);
    return mix(top, bottom, f.y);
}

vec3 to_linear(vec3 s) {
    return mix(s / 12.92, pow((s + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), s));
}

// How much closer to the sun the nearest caster over p is, in blocks.
float caster_gap(vec3 p) {
    float su = dot(p, SunU.xyz) + SunU.w;
    float sv = dot(p, SunV.xyz) + SunV.w;
    vec2 uv = vec2(su * SunScale.x, sv * SunScale.y) * 0.5;
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
        return 0.0;
    }
    float occluder = mix(SunScale.z, SunScale.w, texture(ShadowMap, uv).r);
    return occluder - dot(p, SunDir.xyz);
}

// 1 where the sun reaches p, 0 where a block within the shadow distance is in the way. The
// tracer aims each sample's shadow ray at a slightly different sun; a ray toward a nudged sun
// drifts by the nudge times the distance to the caster, so look again that far over.
float sunlight(vec3 p, vec3 jitter) {
    float gap = caster_gap(p);
    if (gap <= Shadow.x || gap >= SunDir.w) {
        return 1.0;
    }
    float nudged = caster_gap(p + jitter * gap);
    return nudged > Shadow.x && nudged < SunDir.w ? 0.0 : 1.0;
}

// The direct and ambient light on a face, before its colour.
vec3 light(vec3 p, bool top, float ao, float sky) {
    int sampleIndex = (int(gl_FragCoord.x) & 1) + 2 * (int(gl_FragCoord.y) & 1);
    vec3 jitter = Jitter[sampleIndex].xyz;
    vec3 sun = SunDir.xyz + jitter;
    float lambert = top ? 1.0 : max(0.0, sun.z) / sun.y;
    float direct = 0.0;
    if (lambert > 0.0) {
        // Off the face a little, toward the open side, so the face doesn't shade itself.
        vec3 normal = top ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
        direct = sunlight(p + normal * 0.06, jitter) * Light.y * lambert;
    }
    float ambient = Light.x * ao;
    if (Light.z > 0.0) {
        ambient *= 1.0 - Light.z * (1.0 - (0.15 + 0.85 * sky));
    }
    vec3 l = ambient * ShadowTint.rgb + direct * SunTint.rgb;
    if (Light.w != 0.0) {
        l *= 1.0 + Light.w * clamp((p.y - 64.0) / 64.0, -1.0, 1.0);
    }
    return l;
}
#endif

void main() {
#ifndef SHADOW
    bool top = faceInfo.x == 0;
    vec3 albedo = to_linear(faceColor.rgb) * 2.0;
#ifdef WATER
    // The water's colour and the light on its surface; the composite mixes it by depth.
    vec3 normal = top ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
    float ambient = Light.x;
    if (Light.z > 0.0) {
        ambient *= 1.0 - Light.z * (1.0 - (0.15 + 0.85 * bilinear(faceSky, top ? faceUv : vec2(0.5))));
    }
    float lit = ambient + sunlight(localPos + normal * 0.06, vec3(0.0)) * Light.y;
    if (Light.w != 0.0) {
        lit *= 1.0 + Light.w * clamp((localPos.y - 64.0) / 64.0, -1.0, 1.0);
    }
    fragColor = vec4(albedo, lit);
#elif defined(TINTED)
    // Plants and glass: their own weight goes in alpha, and the blend keeps what's behind.
    vec3 l = light(localPos, top, 1.0, bilinear(faceSky, faceUv));
    fragColor = vec4(albedo * l, faceColor.a);
#else
    if ((faceInfo.y & FLAG_UNKNOWN) != 0) {
        // A chunk the map has no data for: the tracer's ray stops here and leaves the pixel unknown.
        fragColor = vec4(0.0);
        return;
    }
    float ao = bilinear(faceOcclusion, faceUv);
    vec3 l = light(localPos, top, ao, bilinear(faceSky, faceUv));
    float emission = faceColor.a;
    if (emission > 0.0) {
        l = max(l, vec3(0.35 + emission * 0.85));
    }
    if (!top) {
        // Faces that aren't up read darker, the way Minecraft shades its own block sides.
        l *= 0.82;
    }
    fragColor = vec4(albedo * l, 1.0);
#endif
#endif
}
