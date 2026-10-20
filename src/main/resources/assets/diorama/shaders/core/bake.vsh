#version 330
#extension GL_ARB_separate_shader_objects : require

#include <diorama:bake.glsl>

// One instance per block face: the cell, the face (low four bits of w) and its flags.
layout(location = 0) in vec4 Corner;
layout(location = 1) in ivec4 Cell;
#ifndef SHADOW
layout(location = 2) in vec4 Color;
layout(location = 3) in vec4 Occlusion;
layout(location = 4) in vec4 FaceLight;

// The solid blocks around the tile: a section table, then 128 words of bits per section.
uniform usamplerBuffer Voxels;

layout(location = 0) out vec3 localPos;
layout(location = 1) out vec2 faceUv;
layout(location = 2) flat out vec4 faceColor;
layout(location = 3) flat out vec4 faceOcclusion;
layout(location = 4) flat out vec4 faceLight;
layout(location = 5) flat out ivec2 faceInfo;
layout(location = 6) out float vertexSky;

const uint SECTION_UNKNOWN = 0xFFFFFFFEu;
const uint SECTION_EMPTY = 0xFFFFFFFFu;

// 1 if the block is solid, 0 if not, -1 in a chunk the map has no data for.
int solid_at(ivec3 c) {
    ivec3 s = (c >> 4) - VoxelOrigin.xyz;
    if (any(lessThan(s, ivec3(0))) || any(greaterThanEqual(s, VoxelSize.xyz))) {
        return 0;
    }
    uint entry = texelFetch(Voxels, (s.y * VoxelSize.z + s.z) * VoxelSize.x + s.x).r;
    if (entry == SECTION_EMPTY) {
        return 0;
    }
    if (entry == SECTION_UNKNOWN) {
        return -1;
    }
    int i = ((c.y & 15) << 8) | ((c.z & 15) << 4) | (c.x & 15);
    uint word = texelFetch(Voxels, int(entry) + (i >> 5)).r;
    return int((word >> uint(i & 31)) & 1u);
}

// TileBaker.blocked: walks from o along d for up to dist blocks; true if a solid block is in the way.
bool blocked(vec3 o, vec3 d, float dist, int steps) {
    ivec3 c = ivec3(floor(o));
    int top = VoxelOrigin.w;
    if (c.y < top && solid_at(c) == 1) {
        return true;
    }
    ivec3 st = ivec3(d.x > 0.0 ? 1 : -1, d.y > 0.0 ? 1 : -1, d.z > 0.0 ? 1 : -1);
    vec3 tDelta = vec3(d.x == 0.0 ? 1e30 : abs(1.0 / d.x), d.y == 0.0 ? 1e30 : abs(1.0 / d.y), d.z == 0.0 ? 1e30 : abs(1.0 / d.z));
    vec3 tMax = vec3(
        d.x == 0.0 ? 1e30 : (d.x > 0.0 ? float(c.x) + 1.0 - o.x : o.x - float(c.x)) * tDelta.x,
        d.y == 0.0 ? 1e30 : (d.y > 0.0 ? float(c.y) + 1.0 - o.y : o.y - float(c.y)) * tDelta.y,
        d.z == 0.0 ? 1e30 : (d.z > 0.0 ? float(c.z) + 1.0 - o.z : o.z - float(c.z)) * tDelta.z
    );
    for (int i = 0; i < steps; i++) {
        if (min(tMax.x, min(tMax.y, tMax.z)) > dist) {
            return false;
        }
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            c.x += st.x;
            tMax.x += tDelta.x;
        } else if (tMax.y < tMax.z) {
            c.y += st.y;
            tMax.y += tDelta.y;
        } else {
            c.z += st.z;
            tMax.z += tDelta.z;
        }
        if (c.y >= top) {
            return false;
        }
        int s = solid_at(c);
        if (s < 0) {
            return false;
        }
        if (s == 1) {
            return true;
        }
    }
    return false;
}

// TileBaker.skyAt: the share of the sky seen from a block corner.
float sky_at(vec3 corner) {
    float open = 0.0;
    float total = 0.0;
    for (int r = 0; r < 13; r++) {
        vec3 d = SkyRays[r].xyz;
        if (!blocked(corner + d * 1e-3, d, 12.0, 36)) {
            open += SkyRays[r].w;
        }
        total += SkyRays[r].w;
    }
    return open / total;
}
#endif

void main() {
    vec2 c = Corner.xy;
    int face = Cell.w & 15;
    vec3 base = vec3(Cell.xyz);
    vec3 p;
    if (face == 0) {
        // Top: corners nw, ne, sw, se.
        p = base + vec3(c.x, 1.0, c.y);
    } else if (face == 1) {
        // South: top west, top east, bottom west, bottom east.
        p = base + vec3(c.x, 1.0 - c.y, 1.0);
    } else if (face == 2) {
        // West, for the shadow map only.
        p = base + vec3(0.0, c.x, c.y);
    } else {
        // Floor: the bottom of the cell.
        p = base + vec3(c.x, 0.0, c.y);
    }
#ifdef SHADOW
    float su = dot(p, SunU.xyz) + SunU.w;
    float sv = dot(p, SunV.xyz) + SunV.w;
    float d = (dot(p, SunDir.xyz) - SunScale.z) / (SunScale.w - SunScale.z);
    gl_Position = vec4(su * SunScale.x - 1.0, sv * SunScale.y - 1.0, bake_ndc_depth(d), 1.0);
#else
    float v = p.z * Camera.x - p.y * Camera.y + Camera.z;
    float t = -(p.y * Camera.x + p.z * Camera.y) + Camera.w;
    float d = (DepthRange.y - t) / (DepthRange.y - DepthRange.x);
    // 128 blocks across the tile at 4 pixels each, plus a pixel all round for outlines; the
    // north edge lands on the first rows of the texture.
    gl_Position = vec4((p.x * 4.0 + 1.0) / 257.0 - 1.0, (v * 4.0 + 1.0) / 257.0 - 1.0, bake_ndc_depth(d), 1.0);
    localPos = p;
    faceUv = c;
    faceColor = Color;
    faceOcclusion = Occlusion;
    faceLight = FaceLight;
    faceInfo = ivec2(face, Cell.w >> 4);
    vertexSky = 1.0;
    if (Mode.x < 0.5 && Light.z > 0.0 && face <= 1 && ((Cell.w >> 4) & 1) == 0) {
        // Sky occlusion at this corner, between corners like the tracer's per-corner values.
#ifdef WATER
        // The tracer reads water's light over the top of the cell it entered.
        vec3 corner = face == 0 ? p : base + vec3(c.x, 1.0, 1.0);
#else
        vec3 corner = p;
#endif
        vertexSky = sky_at(corner);
    }
#endif
}
