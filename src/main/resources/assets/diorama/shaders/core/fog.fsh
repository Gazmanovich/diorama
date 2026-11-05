#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
//?}

// Volumetric-looking fog over the parts of the map with no data.
//
// The fog is a height field: deep in unknown territory it stands fogHeight blocks tall, and it
// thins out toward known ground, with noise breaking up the edge. We look at it through the same
// tilted camera as the map, so we march a few height layers from the top down. A layer at height h
// sits above the ground point h * cos(pitch) further south, which is what makes the banks look
// like they rise off the terrain.

//? if >=26.3 {
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
//?} else if >=1.21.11 {
/*layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
*///?} else if <1.21.6 {
/*// Before 1.21.6 uniforms were set one by one, and only the ones the shader names.
uniform float GameTime;
uniform vec4 ColorModulator;
*///?} else {
/*// Before 1.21.11 the globals started at the screen size, and the transforms ended with a line width.
layout(std140) uniform Globals {
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};
*///?}

// Share of known ground per mask texel, in the red channel. Green is 1 where every texel this
// shader can read from a pixel in that texel is fully known, blue is 1 where every one is fully
// unknown. Alpha is how tall the fog stands, as a share of its height: 1 but over a remembered
// map, where it rolls up from the land already redrawn to the full height at the unexplored.
uniform sampler2D Sampler0;
// Tileable noise covering NOISE_PERIOD map units, a different scale per channel.
uniform sampler2D Sampler1;
// What would otherwise be worked out from the mask for every pixel, worked out once instead
// (MapViewport.writeCells): in red each cloud cell's level in fifths, a texel a cell from
// CELL_MARGIN cells before the mask's first, in green the same for cells twice the size while the
// zoom hands over, and in blue, a texel a mask texel, the known share blurred as knownSoft reads
// it. Alpha at (0, 0) is 1 when the cells are there; with too many for the texture they aren't.
// Elsewhere alpha is, for cells of the size drawn, the lowest and highest level of the cells from
// three before to four after each way, as (low * 6 + high) * 7 / 255.
uniform sampler2D Sampler2;
const int CELL_MARGIN = 7;
const float SOFT_TEXELS = 256.0;

//? if >=26.3 {
layout(location = 0) in vec2 maskUv;
//?} else {
/*in vec2 maskUv;
*///?}
// rg: noise offset in eighths of the coarser noise period, in the top three bits of each; r's low
// five bits: how see-through the fog is, in 31sts (0 is opaque); b: cave flag (128), fog scale steps
// (bits 4-6) and sixteenths of a step (bits 0-3); a: mask texel size code (bits 4-7), the fast
// quality flag (4) and the smoke flag (1).
//? if >=26.3 {
layout(location = 1) in vec4 params;

layout(location = 0) out vec4 fragColor;
//?} else {
/*in vec4 params;

out vec4 fragColor;
*///?}

const float MASK_TEXELS = 256.0;
const float NOISE_PERIOD = 512.0;
// The default camera angle (50 degrees). At other angles the banks sit slightly too far north or south.
const float COS_PITCH = 0.643;
const int LAYERS = 8;
// How much of the soft fog is drawn over the cloud terraces.
const float FOG_MIX = 0.5;
// Fast quality, for slower graphics cards: fewer layers, one blur, and no sun shading.
const int FAST_LAYERS = 2;
// Next to land already redrawn the soft fog stands this share of its height, and covers this much
// of the old map under it; both rise to the full fog toward the unexplored.
const float LOWEST = 0.12;
const float MEMORY_COVER = 0.0;
// How much each height layer adds, and how dark the side of a bank or a level away from the sun gets.
const float LAYER_ALPHA = 0.45;
const float SHADOW = 1.6;
// How much of a remembered map under the clouds shows through them.
const float MEMORY_SEE = 0.15;
// The fog's lift right here, set once in main.
float liftHere = 1.0;
// Smoke under a roof has no clouds and keeps the fog it always had: full height, lying low over a
// remembered map, whose share is then 1 less the mask's alpha. Banks this share of the height over
// it, with gaps where the noise is low, never thicker than MEMORY_MOST and never thinner than a
// haze, so the old picture shows under them, too little to read in detail.
bool smokeMap = false;
float memory = 0.0;
const float MEMORY_HEIGHT = 0.4;
const float MEMORY_MOST = 0.7;
const float MEMORY_HAZE = 0.16;
// How dark the side of a bank away from the sun gets in the fog without clouds, caves and smoke.
const float FOG_SHADOW = 0.7;

// Over the surface the fog is drawn as terraced clouds, like a crater stepping down to the revealed
// map: a grid of cells CLOUD_CELL map units across (doubling as the map zooms out), each at a level
// that rises with distance from the revealed land, every level a slab stacked on the one below.
// A slab's cells blend so runs of them read as one with round corners, and it bulges at its middle
// height and rounds off toward its top and bottom, so the stacked levels read as layered pillows.
// Lit like a sunny day's clouds: warm sunlight on the upper parts, cool sky light under, hazier the
// deeper a level is.
// 80 cells to the 1024 * 2^steps map units the noise offset wraps over, and every noise scale the
// clouds read a whole fraction of that too, so nothing jumps where the offset wraps.
const float CLOUD_CELL = 12.8;
const float CLOUD_LEVELS = 5.0;
// A level's thickness as a share of a cell, and how much noise breaks up the stairs.
const float LEVEL_THICK = 0.9;
const float STAIR_NOISE = 0.6;
// How sharply cells meet (smaller is boxier), the see-through fringe at a slab's edge, and how a
// slab rounds off toward its top and bottom (higher is boxier).
const float ROUND = 0.45;
const float EDGE_SOFT = 0.5;
const float PROFILE = 4.0;
const float CLOUD_ALPHA = 0.95;
// How much drifting noise eats a level's edges into wisps, and how far into a level it reaches.
const float WISP = 1.5;
// How much a level's edge frays into strands.
const float STRANDS = 1.0;
// How far swirling noise pushes a level's edges, in cells.
const float WARP = 2.0;
// Sky light and sunlight, and how much the level above darkens what's under it toward the sun.
const float AMBIENT = 1.0;
const float SUN = 1.0;
// Sky-coloured haze over the deeper levels, and round the revealed map at the bottom.
const float HAZE = 0.5;
const float HALO = 0.25;
// How much darker the lowest level is than the highest.
const float DEPTH_DARK = 0.65;
// How much a level's top is shaded like a pillow, 0 for flat.
const float PILLOW = 1.0;
// How dark the clouds' shadow is on the revealed map.
const float GROUND_SHADOW = 0.3;
// Map units at uv 0, from the noise origin, so cells stay put as the view moves.
vec2 originMap;
// Whether the detail texture has the cells' levels, the size of the cells drawn, and the cell at
// the detail texture's first texel, for that size and for twice it.
bool cellsReady = false;
float cellSize = 1.0;
ivec2 cellFirst;
ivec2 cellFirst2;

// Surface fog banks are tall and soft. Cave fog hugs the ground with a tight edge, so a
// three-block tunnel you walked down stays readable instead of blurring into the fog.
float fogHeight;
float blurUnits;
float unitsPerUv;
vec2 noiseOrigin;
// The noise pattern grows by this when zoomed far out, so it keeps its size on screen instead of
// repeating every few pixels. The banks keep their size in blocks, so they stay a thin fringe
// around explored land and never bury it.
float fogScale;
float scaleSteps;
float scaleFraction;
float seconds;
bool fast;
// Every mask texel this pixel can read is fully known. Then every thickness is 0 whatever the
// noise, so the texture reads are skipped. The rest still runs: the lowest layers leave a faint
// veil even over known ground.
bool clear;
// Every mask texel this pixel can read is fully unknown: every known share is exactly 0, so the
// mask reads are skipped. The noise still shapes the fog there, so its reads stay.
bool deep;
// Elsewhere blue holds the highest cloud level any cell within the clouds' reach can stand at, in
// 32nds of 255 (MapViewport.writeCloudTops): the march starts just above it, and there is none at 0.
float cloudTop = CLOUD_LEVELS;

// The known share at uv, without the read where the answer is already known.
float knownAt(vec2 uv) {
    return deep ? 0.0 : texture(Sampler0, uv).r;
}

// Surface fog blends a near and a wide blur, so banks reach well over the edge of known land
// and the noise has room to billow. Cave fog stays tight to the explored tunnels. The blurs are
// worked out per mask texel (MapViewport.writeSoft) and read filtered from Sampler2.
float knownSoft(vec2 uv) {
    if (clear) {
        return 1.0;
    }
    if (deep) {
        return 0.0;
    }
    vec2 at = clamp(uv * SOFT_TEXELS, vec2(0.5), vec2(SOFT_TEXELS - 0.5));
    return texture(Sampler2, at / vec2(textureSize(Sampler2, 0))).b;
}

float noiseLayer(vec2 n) {
    // Speeds are whole periods per 1200 s, so the loop of GameTime wraps without a jump.
    float a = texture(Sampler1, n + vec2(5.0, 2.0) * seconds / 1200.0).r;
    float b = texture(Sampler1, n + vec2(-8.0, 3.0) * seconds / 1200.0).g;
    float c = texture(Sampler1, n + vec2(13.0, -9.0) * seconds / 1200.0).b;
    return a * 0.55 + b * 0.3 + c * 0.15;
}

// Noise at the two whole scale steps around fogScale, blended, so zooming never pops.
float noiseAt(vec2 uv) {
    float coarsePeriod = NOISE_PERIOD * exp2(scaleSteps + 1.0);
    vec2 coarse = noiseOrigin + uv * unitsPerUv / coarsePeriod;
    // The fine noise has half the period, so it runs twice as far from the same origin.
    float fine = noiseLayer(coarse * 2.0);
    // At the detailed zoom and closer there is only the one scale: mix() would return fine anyway.
    if (scaleFraction == 0.0) {
        return fine;
    }
    return mix(fine, noiseLayer(coarse), scaleFraction);
}

float thicknessFrom(float known, float n, float lift) {
    float unknown = 1.0 - known;
    // Noise only shapes the edge; where everything is known there is no fog at all.
    float edge = smoothstep(0.0, 0.35, unknown);
    float amount = smoothstep(0.12, 0.75, unknown + (n - 0.5) * 1.5 * edge);
    // Over smoke's memory the noise alone makes banks, with gaps where it's low.
    amount = mix(amount, smoothstep(0.47, 0.66, n) * edge, memory);
    return fogHeight * mix(LOWEST, 1.0, lift) * mix(1.0, MEMORY_HEIGHT, memory) * amount * (0.45 + 0.75 * n);
}

// From this known share up there is no fog whatever the noise: unknown + 0.75 * edge stays below
// the 0.12 where the amount starts, so the noise needn't be read.
const float NO_FOG_KNOWN = 0.95;

float thicknessAt(vec2 uv, float known) {
    return known >= NO_FOG_KNOWN ? 0.0 : thicknessFrom(known, noiseAt(uv), smokeMap ? 1.0 : texture(Sampler0, uv).a);
}

float thickness(vec2 uv) {
    return thicknessAt(uv, knownSoft(uv));
}

// The level of a cloud cell: 0 over revealed land, else 1 to CLOUD_LEVELS, from the mask's climb.
float cellLevel(vec2 cell, float size) {
    vec2 pos = (cell + 0.5) * size;
    vec4 m = texture(Sampler0, (pos - originMap) / unitsPerUv);
    if (m.r >= 0.5) {
        return 0.0;
    }
    // The noise moves the climb by under half of STAIR_NOISE either way: well inside a level it
    // can't change the cell's level, and needn't be read.
    float climb = m.a * CLOUD_LEVELS;
    float within = fract(climb);
    if (within > 0.31 && within < 0.69) {
        return clamp(ceil(climb), 1.0, CLOUD_LEVELS);
    }
    float n = texture(Sampler1, pos / (size * 20.0) + 0.21).r;
    return clamp(ceil(climb + (n - 0.5) * STAIR_NOISE), 1.0, CLOUD_LEVELS);
}

// How much of a point is under cells at level k or above: the four nearest cells blended, with the
// blend squeezed into a band at each cell's edge, so runs of cells read as one with round corners.
// Whether level k's cover is the same all round p, however the edges curl: 1 if every cell a point
// within 1.45 cells of p can read is at k or above, 0 if every one is under it, else -1.
float sameCover(vec2 p, float size, float k) {
    if (!cellsReady || size > cellSize * 1.5) {
        return -1.0;
    }
    float span = floor(texelFetch(Sampler2, ivec2(floor(p / size - 0.5)) - cellFirst, 0).a * 255.0 / 7.0 + 0.5);
    float low = floor(span / 6.0);
    float high = span - low * 6.0;
    return k <= low ? 1.0 : (k > high ? 0.0 : -1.0);
}

float levelCover(vec2 p, float size, float k) {
    float same = sameCover(p, size, k);
    if (same >= 0.0) {
        return same;
    }
    vec2 g = p / size - 0.5;
    vec2 i = floor(g);
    vec2 f = smoothstep(0.5 - ROUND, 0.5 + ROUND, g - i);
    float a;
    float b;
    float c;
    float d;
    if (cellsReady) {
        // Worked out already: the cells' levels from the detail texture.
        bool doubled = size > cellSize * 1.5;
        ivec2 at = ivec2(i) - (doubled ? cellFirst2 : cellFirst);
        vec4 l00 = texelFetch(Sampler2, at, 0);
        vec4 l10 = texelFetch(Sampler2, at + ivec2(1, 0), 0);
        vec4 l01 = texelFetch(Sampler2, at + ivec2(0, 1), 0);
        vec4 l11 = texelFetch(Sampler2, at + ivec2(1, 1), 0);
        vec4 levels = floor((doubled ? vec4(l00.g, l10.g, l01.g, l11.g) : vec4(l00.r, l10.r, l01.r, l11.r)) * CLOUD_LEVELS + 0.5);
        vec4 over = step(vec4(k), levels);
        a = over.x;
        b = over.y;
        c = over.z;
        d = over.w;
    } else {
        a = step(k, cellLevel(i, size));
        b = step(k, cellLevel(i + vec2(1.0, 0.0), size));
        c = step(k, cellLevel(i + vec2(0.0, 1.0), size));
        d = step(k, cellLevel(i + vec2(1.0, 1.0), size));
    }
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// Drifting noise for wisps, 0 to 1, a few times finer than a cell.
float wispAt(vec2 p, float size) {
    vec2 n = p / (size * 4.0);
    float a = texture(Sampler1, n + vec2(5.0, 2.0) * seconds / 1200.0).g;
    float b = texture(Sampler1, n * 2.0 + vec2(-7.0, 4.0) * seconds / 1200.0).b;
    return a * 0.6 + b * 0.4;
}

// A point pushed about by swirling noise, so a level's edges curl and trail into wisps instead of
// running straight. Less at the lowest level, whose edge is the revealed map's.
vec2 warped(vec2 p, float size, float k) {
    vec2 n = p / (size * 2.5);
    float drift = seconds / 1200.0;
    vec2 a = vec2(texture(Sampler1, n + vec2(3.0, 1.0) * drift).g, texture(Sampler1, n + 0.37 + vec2(-2.0, 3.0) * drift).b) - 0.5;
    vec2 b = vec2(texture(Sampler1, n * 3.0 + 0.11).a, texture(Sampler1, n * 3.0 + 0.71).g) - 0.5;
    float amount = WARP * size * (k <= 1.0 ? 0.35 : 1.0);
    return p + (a + b * 0.45) * amount;
}

// Level cover with wisps eaten out of its edges: the noise only takes away, and only where the
// cover is thinning, so the middle of a level stays whole and revealed land stays clear.
// A level's cover at p with its edges curled, before the wisps bite into it.
float curledCover(vec2 p, float size, float k) {
    // The same all round, however they curl: no need to read the curls.
    float same = sameCover(p, size, k);
    return same >= 0.0 ? same : levelCover(warped(p, size, k), size, k);
}

// Wisps bitten out of a curled cover.
float bitten(float cover, vec2 p, float size) {
    // Wholly in or out of the level, the bite takes nothing: no need to read the wisps.
    if (cover <= 0.0 || cover >= 1.0) {
        return cover;
    }
    float bite = smoothstep(0.35, 0.75, wispAt(p, size));
    return cover - WISP * bite * (1.0 - cover) * cover * 2.0;
}

float wispCover(vec2 p, float size, float k) {
    return bitten(curledCover(p, size, k), p, size);
}

// Above 0 inside a cloud. A level's slab needs a quarter of cover at its middle height, three
// quarters at its top and bottom, so each slab rounds off there.
float terraceInside(vec2 p, float h, float size, float level) {
    float k = clamp(ceil(h / level), 1.0, CLOUD_LEVELS);
    float t = pow(clamp(abs(h - (k - 0.5) * level) / (0.5 * level), 0.0, 1.0), PROFILE);
    return wispCover(p, size, k) - (0.25 + 0.5 * t);
}

// The colour of the cloud surface met at height h over a point, and how solid it is there.
// The colour of the cloud surface at height h, from how much cloud lies toward the sun (blocked)
// and how much of the level falls away toward the sun and away from it there (the edges).
vec3 terraceColor(float h, float level, float hTop, float blocked, float sunEdge, float farEdge) {
    vec3 sky = vec3(0.56, 0.65, 0.77) * AMBIENT;
    vec3 sunlight = vec3(1.0, 0.93, 0.82) * SUN;
    vec3 haze = vec3(0.64, 0.75, 0.9);
    float k = clamp(ceil(h / level), 1.0, CLOUD_LEVELS);
    float up = pow(clamp(h / level - (k - 1.0), 0.0, 1.0), 0.8);
    float shading = (0.12 + 0.5 * up) * (2.0 - SHADOW * blocked * (1.0 - up * up));
    // A level's top is a pillow: lit along the edges that face the sun, shaded along the ones
    // that face away.
    shading *= mix(1.0, 1.0 + PILLOW * (0.3 * sunEdge - 0.45 * farEdge), up);
    vec3 color = sky * (1.0 - 0.35 * shading) + sunlight * 0.5 * (0.1 + shading);
    // Higher levels catch more light, deeper ones sit in haze: that's what reads as depth.
    color *= mix(DEPTH_DARK, 1.0, k / CLOUD_LEVELS);
    color = min(color, vec3(1.0));
    return mix(color, haze, HAZE * pow(1.0 - h / hTop, 1.5));
}

vec4 terraceSurface(vec2 p, float h, float size, float level, float hTop, float jitter) {
    float k = clamp(ceil(h / level), 1.0, CLOUD_LEVELS);
    // Two looks toward the sun: a level as high or higher there darkens this point, less near a slab's top.
    vec2 toward = normalize(vec2(-1.0, -0.4)) * size * 0.4;
    float blocked = levelCover(p + toward * (1.0 + jitter * 0.5), size, k) + levelCover(p + toward * (2.0 + jitter * 0.5), size, k + 1.0);
    vec2 sunward = normalize(vec2(-1.0, -0.4)) * size * 0.35;
    float sunEdge = 1.0 - levelCover(p + sunward, size, k);
    float farEdge = 1.0 - levelCover(p - sunward, size, k);
    vec3 color = terraceColor(h, level, hTop, blocked, sunEdge, farEdge);
    // Solid inside; toward the edge it frays into strands drawn out along the wind.
    float curled = curledCover(p, size, k);
    float cover = bitten(curled, p, size);
    float fringe = cover - 0.25;
    float edge = 1.0 - smoothstep(0.55, 0.95, curled);
    float alpha = CLOUD_ALPHA * smoothstep(0.0, EDGE_SOFT, fringe);
    // The strands only show toward the edge: inside a level they needn't be read.
    if (edge > 0.0) {
        vec2 along = vec2(p.x * 0.35, p.y * 1.4);
        float strands = smoothstep(0.25, 0.75, wispAt(along + 53.0, size * 0.5));
        alpha *= mix(1.0, strands, edge * STRANDS);
    }
    return vec4(color, alpha);
}

// Clouds over a ground point at one cell size: premultiplied colour, and how much shows through.
// The line of sight goes on past the first surface to the next one down, so a level under a
// thin one shows faintly through it.
vec4 clouds(vec2 p0, float size, vec3 lit, vec3 shade, float jitter) {
    float level = size * LEVEL_THICK;
    float hTop = CLOUD_LEVELS * level;
    int perLevel = fast ? 2 : 3;
    int steps = int(CLOUD_LEVELS) * perLevel;
    float dh = hTop / float(steps);
    // The steps over the highest level within reach can't meet a cloud: start under them, at the
    // same heights, with the one before as the last point outside.
    int first = int(CLOUD_LEVELS - cloudTop) * perLevel;
    float last = first == 0 ? hTop : hTop - (float(first) - 0.5 + jitter * 0.9) * dh;
    bool wasInside = false;
    int surfaces = 0;
    float through = 1.0;
    vec3 glow = vec3(0.0);
    for (int s = first; s < steps; s++) {
        float h = hTop - (float(s) + 0.5 + jitter * 0.9) * dh;
        bool inside = terraceInside(vec2(p0.x, p0.y + h * COS_PITCH), h, size, level) > 0.0;
        if (inside && !wasInside) {
            // Close in on the surface between the last point outside and this one.
            float above = last;
            float below = h;
            for (int r = 0; r < 3; r++) {
                float mid = 0.5 * (above + below);
                if (terraceInside(vec2(p0.x, p0.y + mid * COS_PITCH), mid, size, level) > 0.0) {
                    below = mid;
                } else {
                    above = mid;
                }
            }
            vec4 hit = terraceSurface(vec2(p0.x, p0.y + below * COS_PITCH), below, size, level, hTop, jitter);
            glow += through * hit.a * hit.rgb;
            through *= 1.0 - hit.a;
            surfaces++;
            if (surfaces >= 2 || through < 0.1) {
                break;
            }
        }
        wasInside = inside;
        last = h;
    }
    return vec4(glow, through);
}

// What clouds() returns over plain never-explored land, where every cell is at the top level and
// so cover is whole at every height: the march's first step is already inside, and its three
// halvings close in from the top to an eighth of that step. There terraceSurface finds the top
// level all round and none above it toward the sun, no edges, and a whole, solid level, so this
// needs no reads at all, and draws the same colour: nothing marks where the march stops.
vec4 flatTop(float size, float jitter) {
    float level = size * LEVEL_THICK;
    float hTop = CLOUD_LEVELS * level;
    float dh = hTop / (CLOUD_LEVELS * (fast ? 2.0 : 3.0));
    float h = hTop - (0.5 + jitter * 0.9) * dh / 8.0;
    vec3 color = terraceColor(h, level, hTop, 1.0, 0.0, 0.0);
    return vec4(color * CLOUD_ALPHA, 1.0 - CLOUD_ALPHA);
}

// The clouds' shadow on the revealed map under a point, 0 to 1.
float groundShadow(vec2 p0, float size) {
    vec2 toward = normalize(vec2(-1.0, -0.4));
    return smoothstep(0.3, 0.6, levelCover(p0 + toward * size * LEVEL_THICK * 1.5, size, 1.0));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    seconds = GameTime * 1200.0;
    // The red byte is the noise offset in its top three bits and, in its low five, how see-through
    // the whole fog is, in 31sts: 0 draws the fog as it is, and the minimap raises it to fade the map.
    vec2 offsetBytes = floor(params.rg * 255.0 + 0.5);
    noiseOrigin = floor(offsetBytes / 32.0) / 8.0;
    float fogOpacity = 1.0 - mod(offsetBytes.r, 32.0) / 31.0;
    float alphaCode = floor(params.a * 255.0 + 0.5);
    unitsPerUv = MASK_TEXELS * 2.0 * exp2(floor(alphaCode / 16.0));
    fast = mod(alphaCode, 16.0) >= 2.0;
    bool smoke = mod(alphaCode, 2.0) >= 1.0;
    float fogCode = floor(params.b * 255.0 + 0.5);
    bool cave = fogCode >= 128.0;
    fogCode = mod(fogCode, 128.0);
    scaleSteps = floor(fogCode / 16.0);
    scaleFraction = mod(fogCode, 16.0) / 16.0;
    fogScale = exp2(scaleSteps + scaleFraction);
    // MapViewport.fogReach repeats how far the reads below can reach (these sizes, the blur taps,
    // the layer shift, the sun probe, and the clouds' cells, curls and southward lean), to know where
    // the clear and deep flags are safe. Change both together.
    fogHeight = cave ? 4.0 : 10.0;
    blurUnits = cave ? 2.5 : 7.0;
    ivec2 texel = clamp(ivec2(floor(maskUv * MASK_TEXELS)), ivec2(0), ivec2(int(MASK_TEXELS) - 1));
    vec4 flags = texelFetch(Sampler0, texel, 0);
    clear = flags.g > 0.5;
    float blue = floor(flags.b * 255.0 + 0.5);
    deep = blue > 200.0;
    cloudTop = deep ? CLOUD_LEVELS : min(CLOUD_LEVELS, floor(blue / 32.0 + 0.5));

    vec3 lit = cave ? vec3(0.24, 0.26, 0.33) : vec3(0.97, 0.97, 0.99);
    vec3 shade = cave ? vec3(0.035, 0.04, 0.055) : vec3(0.48, 0.55, 0.67);
    smokeMap = smoke && !cave;
    if (smokeMap) {
        memory = 1.0 - texture(Sampler0, maskUv).a;
    } else {
        liftHere = texture(Sampler0, maskUv).a;
    }
    if (smokeMap) {
        // Under a roof the banks are smoke, faintly warm from the lava below.
        lit = vec3(0.26, 0.22, 0.21);
        shade = vec3(0.07, 0.055, 0.055);
    }
    vec2 toSun = vec2(-1.0, -0.4) * (9.0 * fogScale / unitsPerUv);

    bool cloudy = false;
    vec3 cloudGlow = vec3(0.0);
    float cloudCover = 0.0;
    if (!cave && !smoke) {
        originMap = noiseOrigin * NOISE_PERIOD * exp2(scaleSteps + 1.0);
        vec2 p0 = originMap + maskUv * unitsPerUv;
        float size = CLOUD_CELL * exp2(scaleSteps);
        float jitter = hash(gl_FragCoord.xy) - 0.5;
        // Cells double in size halfway between two zoom steps, handing over quickly: two layouts
        // blended for long would show as ghost clouds.
        float handover = smoothstep(0.42, 0.58, scaleFraction);
        int perStep = 10;
        if (handover >= 1.0) {
            size *= 2.0;
            perStep = 5;
        }
        // The noise origin is ten cells of the base size from map 0 for each eighth of the coarse
        // noise period it moves.
        ivec2 originCells = ivec2(floor(offsetBytes / 32.0));
        cellsReady = texelFetch(Sampler2, ivec2(0), 0).a > 0.5;
        cellSize = size;
        cellFirst = originCells * perStep - CELL_MARGIN;
        cellFirst2 = originCells * (perStep / 2) - CELL_MARGIN;
        vec4 c;
        float shadow = 0.0;
        if (clear || cloudTop < 0.5) {
            // No cloud within reach of here: none here.
            c = vec4(0.0, 0.0, 0.0, 1.0);
            handover = 0.0;
        } else if (deep) {
            // Never explored and plain all around: the highest level's top, and its shadow
            // all over the ground under it.
            c = flatTop(size, jitter);
            shadow = fast ? 0.0 : GROUND_SHADOW;
        } else {
            c = clouds(p0, size, lit, shade, jitter);
            shadow = fast ? 0.0 : GROUND_SHADOW * groundShadow(p0, size);
        }
        if (handover > 0.0 && handover < 1.0) {
            vec4 c2 = deep ? flatTop(size * 2.0, jitter) : clouds(p0, size * 2.0, lit, shade, jitter);
            float shadow2 = fast ? 0.0 : GROUND_SHADOW * (deep ? 1.0 : groundShadow(p0, size * 2.0));
            c = mix(c, c2, handover);
            shadow = mix(shadow, shadow2, handover);
        }
        vec3 glow = c.rgb;
        float through = c.a;
        // Round the revealed map at the bottom, a soft sky-coloured haze against the cloud walls;
        // where there's no map at all, the same haze fills what the rounded edges leave.
        float soft = knownSoft(maskUv);
        float halo = HALO * (1.0 - smoothstep(0.55, 1.0, soft));
        float empty = 1.0 - smoothstep(0.3, 0.85, knownAt(maskUv));
        float mist = max(halo, empty);
        glow += through * mist * vec3(0.64, 0.75, 0.9);
        through *= 1.0 - mist;
        cloudGlow = glow;
        cloudCover = 1.0 - through * (1.0 - shadow);
        cloudy = true;
    }

    vec3 color = vec3(0.0);
    float alpha = 0.0;
    float jitter = hash(gl_FragCoord.xy) - 0.5;
    int layers = fast ? FAST_LAYERS : LAYERS;
    for (int i = 0; i < layers; i++) {
        float h = fogHeight * (1.0 - (float(i) + 0.5 + jitter * 0.6) / float(layers));
        vec2 ground = maskUv + vec2(0.0, h * COS_PITCH / unitsPerUv);
        float th = thickness(ground);
        float a = smoothstep(h - 1.4, h + 1.4, th);
        if (a <= 0.002) {
            continue;
        }
        // More fog between this point and the sun means it sits in shadow.
        float occlusion = 0.0;
        if (!fast) {
            vec2 probe = ground + toSun;
            float toward = thicknessAt(probe, clear ? 1.0 : knownAt(probe));
            occlusion = clamp((toward - th) / fogHeight * 2.2, 0.0, 1.0);
        }
        float light = clamp(0.2 + 0.7 * (h / fogHeight) + 0.3 - occlusion * (cloudy ? SHADOW : FOG_SHADOW), 0.0, 1.0);
        vec3 layerColor = mix(shade, lit, light);
        // Fewer layers each carry more, so the bank stays as thick.
        float layerAlpha = a * (fast ? 0.85 : LAYER_ALPHA);
        color += (1.0 - alpha) * layerAlpha * layerColor;
        alpha += (1.0 - alpha) * layerAlpha;
        if (alpha > 0.985) {
            break;
        }
    }

    // The ground under a thick bank is never visible.
    float soft = knownSoft(maskUv);
    // Low fog over a remembered map keeps the old map under it.
    float floorFog = smoothstep(0.5, 2.5, thicknessAt(maskUv, soft)) * smoothstep(0.3, 0.9, liftHere) * (1.0 - memory);
    color += (1.0 - alpha) * floorFog * shade;
    alpha += (1.0 - alpha) * floorFog;

    // Ground that is known right here stays visible; fog only rolls over its edges. Caves use the
    // sharp mask so narrow tunnels stay clear; the surface uses the blurred one so banks stay soft.
    float here = cave ? knownAt(maskUv) : soft;
    alpha *= 1.0 - 0.85 * smoothstep(0.45, 0.95, here);

    // Where there is no map at all, nothing should show through, or the edge of the data would
    // read as a hard line inside the bank.
    // The cover takes the colour the layers already have here, so it doesn't show as a band.
    // A remembered map is only covered up as its fog reaches the full height.
    float empty = (1.0 - smoothstep(0.3, 0.85, knownAt(maskUv))) * mix(MEMORY_COVER, 1.0, smoothstep(0.7, 1.0, liftHere)) * (1.0 - memory);
    vec3 coverColor = alpha > 0.02 ? color / alpha : mix(shade, lit, 0.7);
    color += (1.0 - alpha) * empty * coverColor;
    alpha += (1.0 - alpha) * empty;

    if (memory > 0.0) {
        vec3 straight = alpha > 0.002 ? color / alpha : mix(shade, lit, 0.75);
        // The haze only where the new map isn't yet: it goes as tiles land.
        float low = mix(alpha, clamp(alpha, MEMORY_HAZE * (1.0 - soft), MEMORY_MOST), memory);
        color = straight * low;
        alpha = low;
    }

    if (cloudy) {
        // The fog rolls over the cloud terraces: its premultiplied colour over theirs.
        float a = alpha * FOG_MIX;
        vec3 premul = color * FOG_MIX + (1.0 - a) * cloudGlow;
        alpha = 1.0 - (1.0 - a) * (1.0 - cloudCover);
        color = premul;
        // A remembered map shows through a little: its share is in green where that isn't the clear
        // flag. Read filtered, so it changes smoothly between texels rather than a texel at a time.
        float remembered = clear ? 0.0 : clamp(texture(Sampler0, maskUv).g / 0.4, 0.0, 1.0);
        float keep = 1.0 - MEMORY_SEE * remembered;
        color *= keep;
        alpha *= keep;
    }
    if (alpha <= 0.002) {
        discard;
    }
    color += (hash(gl_FragCoord.yx) - 0.5) / 255.0;
    fragColor = vec4(color / alpha, alpha * fogOpacity) * ColorModulator;
}
