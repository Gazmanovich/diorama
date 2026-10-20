#version 330
#extension GL_ARB_separate_shader_objects : require

#include <diorama:bake.glsl>

// One instance per block face: the cell, the face (low four bits of w) and its flags.
layout(location = 0) in vec4 Corner;
layout(location = 1) in ivec4 Cell;
#ifndef SHADOW
layout(location = 2) in vec4 Color;
layout(location = 3) in vec4 Occlusion;
layout(location = 4) in vec4 Sky;

layout(location = 0) out vec3 localPos;
layout(location = 1) out vec2 faceUv;
layout(location = 2) flat out vec4 faceColor;
layout(location = 3) flat out vec4 faceOcclusion;
layout(location = 4) flat out vec4 faceSky;
layout(location = 5) flat out ivec2 faceInfo;
#endif

void main() {
    vec2 c = Corner.xy;
    int face = Cell.w & 15;
    vec3 p = vec3(Cell.xyz);
    if (face == 0) {
        // Top: corners nw, ne, sw, se.
        p += vec3(c.x, 1.0, c.y);
    } else if (face == 1) {
        // South: top west, top east, bottom west, bottom east.
        p += vec3(c.x, 1.0 - c.y, 1.0);
    } else {
        // West, for the shadow map only.
        p += vec3(0.0, c.x, c.y);
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
    // 128 blocks across the tile; its north edge lands on the first row of the texture.
    gl_Position = vec4(p.x / 64.0 - 1.0, v / 64.0 - 1.0, bake_ndc_depth(d), 1.0);
    localPos = p;
    faceUv = c;
    faceColor = Color;
    faceOcclusion = Occlusion;
    faceSky = Sky;
    faceInfo = ivec2(face, Cell.w >> 4);
#endif
}
