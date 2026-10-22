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

// One instance per block face: the cell, the face (low four bits of w) and its flags.
LOC(0) in vec4 Corner;
LOC(1) in ivec4 Cell;
#ifndef SHADOW
LOC(2) in vec4 Color;
LOC(3) in vec4 Occlusion;
LOC(4) in vec4 FaceLight;
// Where the face's four corners are in the sky texture, a slot each, or -1 for none.
LOC(5) in int SkyIndex;
// The piece of the cell the face covers, in 128ths: x0, y0, z0 a byte each in x, x1, y1, z1 in y.
// The whole cell for a cube; part of a box of a smaller block, as ShapeFaces packs it.
LOC(6) in ivec2 Box;
#else
LOC(2) in ivec2 Box;
#endif

const int FLAG_UNKNOWN = 1;
const int FLAG_WATER = 256;
const int SKY_WIDTH = 2048;
const int SKY_HEIGHT = 1024;

#ifdef SKY_PASS
// The solid blocks around the tile: a section table, then 128 words of bits per section.
uniform usampler2D Voxels;

LOC(0) flat out float cornerSky;

const int VOXEL_WIDTH = 4096;
const uint SECTION_UNKNOWN = 0xFFFFFFFEu;
const uint SECTION_EMPTY = 0xFFFFFFFFu;

uint voxel(int i) {
    return texelFetch(Voxels, ivec2(i % VOXEL_WIDTH, i / VOXEL_WIDTH), 0).r;
}

// 1 if the block is solid, 0 if not, -1 in a chunk the map has no data for.
int solid_at(ivec3 c) {
    ivec3 s = (c >> 4) - VoxelOrigin.xyz;
    if (any(lessThan(s, ivec3(0))) || any(greaterThanEqual(s, VoxelSize.xyz))) {
        return 0;
    }
    uint entry = voxel((s.y * VoxelSize.z + s.z) * VoxelSize.x + s.x);
    if (entry == SECTION_EMPTY) {
        return 0;
    }
    if (entry == SECTION_UNKNOWN) {
        return -1;
    }
    int i = ((c.y & 15) << 8) | ((c.z & 15) << 4) | (c.x & 15);
    uint word = voxel(int(entry) + (i >> 5));
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
#elif !defined(SHADOW)
uniform sampler2D Sky;

LOC(0) out vec3 localPos;
LOC(1) out vec2 faceUv;
LOC(2) flat out vec4 faceColor;
LOC(3) flat out vec4 faceOcclusion;
LOC(4) flat out vec4 faceLight;
LOC(5) flat out ivec2 faceInfo;
LOC(6) flat out vec4 faceSky;
LOC(7) flat out ivec3 faceCell;

float sky_slot(int slot) {
    return texelFetch(Sky, ivec2(slot % SKY_WIDTH, slot / SKY_WIDTH), 0).r;
}
#endif

// The face's piece, low corner then high, in blocks within the cell.
void box_bounds(ivec2 box, out vec3 lo, out vec3 hi) {
    lo = vec3(float(box.x & 255), float((box.x >> 8) & 255), float((box.x >> 16) & 255)) / 128.0;
    hi = vec3(float(box.y & 255), float((box.y >> 8) & 255), float((box.y >> 16) & 255)) / 128.0;
}

// A face's corner c in tile-local blocks: on its box's face, the cell's face for a cube.
vec3 corner_position(int face, vec3 base, vec2 c, vec3 lo, vec3 hi) {
    if (face == 0) {
        // Top: corners nw, ne, sw, se.
        return base + vec3(mix(lo.x, hi.x, c.x), hi.y, mix(lo.z, hi.z, c.y));
    } else if (face == 1) {
        // South: top west, top east, bottom west, bottom east.
        return base + vec3(mix(lo.x, hi.x, c.x), mix(hi.y, lo.y, c.y), hi.z);
    } else if (face == 2) {
        // West, for the shadow map only.
        return base + vec3(lo.x, mix(lo.y, hi.y, c.x), mix(lo.z, hi.z, c.y));
    }
    // Floor: the bottom of the cell, or part of it.
    return base + vec3(mix(lo.x, hi.x, c.x), 0.0, mix(lo.z, hi.z, c.y));
}

void main() {
    vec2 c = Corner.xy;
    int face = Cell.w & 15;
    int flags = Cell.w >> 4;
    vec3 base = vec3(Cell.xyz);
    vec3 lo;
    vec3 hi;
#ifdef SKY_PASS
    // Sky occlusion is read at the cube's corners, as the tracer does for a smaller block too.
    lo = vec3(0.0);
    hi = vec3(1.0);
#else
    box_bounds(Box, lo, hi);
#endif
    vec3 p = corner_position(face, base, c, lo, hi);
#ifdef SHADOW
    float su = dot(p, SunU.xyz) + SunU.w;
    float sv = dot(p, SunV.xyz) + SunV.w;
    float d = (dot(p, SunDir.xyz) - SunScale.z) / (SunScale.w - SunScale.z);
    gl_Position = vec4(su * SunScale.x - 1.0, sv * SunScale.y - 1.0, bake_ndc_depth(d), 1.0);
#elif defined(SKY_PASS)
    // One point per face corner, into its slot of the sky texture: the corner's sky occlusion,
    // worked out once, which the face then blends between its corners like the tracer.
    int slot = SkyIndex * 4 + VERTEX_INDEX;
    gl_Position = vec4((float(slot % SKY_WIDTH) + 0.5) / float(SKY_WIDTH) * 2.0 - 1.0, (float(slot / SKY_WIDTH) + 0.5) / float(SKY_HEIGHT) * 2.0 - 1.0, 0.0, 1.0);
    gl_PointSize = 1.0;
    cornerSky = 1.0;
    if (face <= 1 && (flags & FLAG_UNKNOWN) == 0) {
        // The tracer reads water's light over the top of the cell it entered.
        vec3 corner = (flags & FLAG_WATER) != 0 && face == 1 ? base + vec3(c.x, 1.0, 1.0) : p;
        cornerSky = sky_at(corner);
    }
#else
    float v = p.z * Camera.x - p.y * Camera.y + Camera.z;
    float t = -(p.y * Camera.x + p.z * Camera.y) + Camera.w;
    float d = (DepthRange.y - t) / (DepthRange.y - DepthRange.x);
    // 128 blocks across the tile at 4 pixels each, plus a pixel all round for outlines; the
    // north edge lands on the first rows of the texture.
    gl_Position = vec4((p.x * 4.0 + 1.0) / 257.0 - 1.0, (v * 4.0 + 1.0) / 257.0 - 1.0, bake_ndc_depth(d), 1.0);
    localPos = p;
    // Where on the cube's face this corner is: occlusion and sky blend across the cube's face, at
    // the same place for a smaller block's face as TileBaker.shade reads them.
    faceUv = face == 1 ? vec2(mix(lo.x, hi.x, c.x), 1.0 - mix(hi.y, lo.y, c.y)) : vec2(mix(lo.x, hi.x, c.x), mix(lo.z, hi.z, c.y));
    faceColor = Color;
    faceOcclusion = Occlusion;
    faceLight = FaceLight;
    faceInfo = ivec2(face, flags);
    faceCell = Cell.xyz;
    faceSky = vec4(1.0);
    if (SkyIndex >= 0) {
        int slot = SkyIndex * 4;
        // Slots follow the quad's vertex order (nw, ne, se, sw); faces blend nw, ne, sw, se.
        faceSky = vec4(sky_slot(slot), sky_slot(slot + 1), sky_slot(slot + 3), sky_slot(slot + 2));
    }
#endif
}
