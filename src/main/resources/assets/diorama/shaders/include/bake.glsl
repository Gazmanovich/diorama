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
    // x: shadow map bias in blocks
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
    vec4 Haze;
};

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
