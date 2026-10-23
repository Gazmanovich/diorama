// What every pass of a GPU tile bake shares. Positions are in tile-local blocks: x from the tile's
// west edge, y as in the world, z from a section-aligned origin north of the tile.
layout(std140) uniform DioramaBake {
    // sin and cos of the camera pitch; v - minV = z * sin - y * cos + Camera.z; depth t = -(y * sin + z * cos) + Camera.w
    vec4 Camera;
    // Nearest and farthest camera depth in the tile, in blocks.
    vec4 DepthRange;
    // Toward the sun, normalised; w is the longest shadow in blocks.
    vec4 SunDir;
    // The shadow map's axes across the sun: u = dot(p, SunU.xyz) + SunU.w, in blocks.
    vec4 SunU;
    vec4 SunV;
    // x and y: 2 / extent along SunU and SunV; z and w: the nearest and farthest depth toward the sun.
    vec4 SunScale;
    // ambient, sun strength, sky occlusion, height shade
    vec4 Light;
    vec4 SunTint;
    vec4 ShadowTint;
    // saturation, exposure, outline, clear water
    vec4 Grade;
    // x: shadow map bias in blocks; y: how much corner occlusion dims the sunlight (MapStyle.cornerOcclusion)
    vec4 Shadow;
    // The tracer nudges the sun a little per sample, which softens shadow edges.
    vec4 Jitter[4];
    // x: 1 when lit by block and sky light (caves, under a roof); y: 1 for a cave slice; z: the y rays start at;
    // w: 1 under a roof, lit from above as if the rock over the open space were lifted off
    vec4 Mode;
    // The voxel table's first section (x and z relative to the origin), and in w the top a sky ray can meet.
    ivec4 VoxelOrigin;
    // The voxel table's size in sections.
    ivec4 VoxelSize;
    // The tracer's sky occlusion rays: direction, and in w how much each counts.
    vec4 SkyRays[13];
    // Under a roof: where the biome's fog starts, how fast it thickens, the most of it, and the block light glow.
    vec4 Lid;
    // Under a roof: the fog's light, and the most light a biome's fog colour may have before it.
    vec4 HazeLight;
};

#ifdef VOXEL_WALK
// The solid blocks around the tile, for sky occlusion and the shadows the sun's map misses: a
// section table, then 128 words of bits per section, and where the entry's top bit is set 128
// more marking the solid blocks smaller than a cube.
uniform usampler2D Voxels;


const int VOXEL_WIDTH = 4096;
const uint SECTION_UNKNOWN = 0xFFFFFFFEu;
const uint SECTION_EMPTY = 0xFFFFFFFFu;

uint voxel(int i) {
    return texelFetch(Voxels, ivec2(i % VOXEL_WIDTH, i / VOXEL_WIDTH), 0).r;
}

// 1 if the block is solid, 0 if not, -1 in a chunk the map has no data for. With cubesOnly a
// smaller block counts as open.
int solid_at(ivec3 c, bool cubesOnly) {
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
    int words = int(entry & 0x7FFFFFFFu);
    uint bit = (voxel(words + (i >> 5)) >> uint(i & 31)) & 1u;
    if (bit != 0u && cubesOnly && (entry & 0x80000000u) != 0u) {
        bit &= ~(voxel(words + 128 + (i >> 5)) >> uint(i & 31)) & 1u;
    }
    return int(bit);
}

// TileBaker.blocked: walks from o along d for up to dist blocks; true if a solid block is in the
// way. A smaller block counts as a cube, or with cubesOnly as open.
bool blocked(vec3 o, vec3 d, float dist, int steps, bool cubesOnly) {
    ivec3 c = ivec3(floor(o));
    int top = VoxelOrigin.w;
    if (c.y < top && solid_at(c, cubesOnly) == 1) {
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
        int s = solid_at(c, cubesOnly);
        if (s < 0) {
            return false;
        }
        if (s == 1) {
            return true;
        }
    }
    return false;
}
#endif

// Reversed depth like vanilla: near is 1, far is 0.
float bake_ndc_depth(float window) {
#if defined(RENDERPEARL_DEPTH_IS_ZERO_TO_ONE) || defined(DIORAMA_DEPTH_ZERO_TO_ONE)
    return window;
#else
    return window * 2.0 - 1.0;
#endif
}

// Camera depth in blocks from a sampled bake depth.
float bake_depth(float window) {
    return mix(DepthRange.y, DepthRange.x, window);
}
