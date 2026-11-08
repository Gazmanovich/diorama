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
// (MapViewport.writeCells). Blue: a texel a mask texel, the known share blurred as knownSoft reads
// it, and beside it (from x 256) the signed distance from explored land, two map units a step, 128
// at its edge. Red and green: a texel a cloud cell of the grid drawn, from CELL_MARGIN cells before
// the mask's first, the cell's VanillaClouds code at 1, 2, 4 and 8 times the base cell, three bits
// each (red the two smaller, green the two bigger). Alpha: the layers any cell within reach has, and
// whether all of them are the deck alone (MapViewport.cloudNear); at (0, 0), 1 when the cells are there.
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
// Fast quality, for slower graphics cards: fewer layers, one blur, and no sun shading.
const int FAST_LAYERS = 2;
// Next to land already redrawn the soft fog stands this share of its height, and covers this much
// of the old map under it; both rise to the full fog toward the unexplored.
const float LOWEST = 0.12;
const float MEMORY_COVER = 0.0;
// How much each height layer adds.
const float LAYER_ALPHA = 0.45;
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
// How dark the side of a bank away from the sun gets: caves and smoke.
const float FOG_SHADOW = 0.7;

// Over the surface the fog is Minecraft's own clouds (VanillaClouds): cells of vanilla's clouds.png,
// in three layers stacked like terraces, each a slab with a flat top and darker south side as
// vanilla draws its clouds, corners a little rounded, soft shadows from the layers above, cooler and
// less white than vanilla's. See-through near explored land, solid over land never explored. The
// smallest cell is CLOUD_CELL map units; zoomed out the cells double in size to stay about
// CLOUD_PIXELS across, each size growing into the next through the zoom step.
const float CLOUD_CELL = 12.8;
const float CLOUD_PIXELS = 40.0;
// Cell sizes drawn: 1, 2, 4, 8 and 16 times the base cell (MapViewport.CLOUD_SIZES).
const int CLOUD_SIZES = 5;
// How far into explored land the see-through clouds reach for the smallest cells (VanillaClouds.RAMP).
const float CLOUD_RAMP = 112.0;
// How sharply cells meet (smaller is boxier), and a slab's thickness as a share of its cell.
const float CLOUD_ROUND = 0.2;
const float CLOUD_THICK = 0.55;
const vec3 CLOUD_TOP = vec3(0.85, 0.865, 0.905);
const vec3 CLOUD_SIDE_HIGH = vec3(0.72, 0.75, 0.84);
const vec3 CLOUD_SIDE_LOW = vec3(0.55, 0.59, 0.72);
const vec3 CLOUD_SHADED = vec3(0.70, 0.73, 0.83);
// How dark the see-through clouds' shadow is on explored land.
const float GROUND_SHADOW = 0.22;
// How much of a remembered map shows under the cover over it, close to the land already redrawn.
const float MEMORY_SEE = 0.4;
// Map units at uv 0, from the noise origin, so cells stay put as the view moves.
vec2 originMap;
// Whether the detail texture has the cells, the first cell's place there, the base cell's size, and
// how many base cells a cell of the grid drawn is.
bool cellsReady = false;
ivec2 cellFirst;
float cloudBase = CLOUD_CELL;
int gridMult = 1;
// The two cell sizes drawn, as powers of two of the base (cloudSize and the next), how far the
// first has grown into the second, 0 to 1, and the slow light over big masses.
int cloudSize = 0;
float cloudGrow = 0.0;
float bigLight = 0.5;
float bigShare = 0.0;
// A screen pixel in map units.
float pixelUnits = 1.0;

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

// Signed distance from explored land at map point p, in map units, positive over the unexplored.
float signedAt(vec2 p) {
    vec2 at = clamp((p - originMap) / unitsPerUv * SOFT_TEXELS, vec2(0.5), vec2(SOFT_TEXELS - 0.5)) + vec2(SOFT_TEXELS, 0.0);
    return (texture(Sampler2, at / vec2(textureSize(Sampler2, 0))).b * 255.0 - 128.0) * 2.0;
}

// How much of a point is under a layer (its bit) at a cell size: the four nearest cells blended,
// squeezed into a band at each cell's edge, so runs of cells read as one with slightly rounded
// corners; 0.5 is the edge. Each size's cells have a block of the detail texture (MapViewport.slotX),
// a layer a colour, 0 or 1, so one filtered read does the blend.
bool cloudsLarge = false;
int cloudSmallest = 0;
float cloudRaw(vec2 p, int level, int bit) {
    float slot = cloudsLarge ? 512.0 : 256.0;
    int at = level - cloudSmallest;
    float width = slot / exp2(float(at));
    float x0 = at == 0 ? 0.0 : slot * 2.0 - slot / exp2(float(at - 1));
    float size = cloudBase * exp2(float(level));
    vec2 g = p / size - 0.5;
    vec2 i = floor(g);
    vec2 f = smoothstep(0.5 - CLOUD_ROUND, 0.5 + CLOUD_ROUND, g - i);
    // The cell holding the grid's first, at this size.
    vec2 first = floor(vec2(cellFirst * gridMult) / exp2(float(level)));
    vec2 local = clamp(i - first, vec2(0.0), vec2(width - 2.0));
    vec4 t = texture(Sampler2, (vec2(x0, 256.0) + local + 0.5 + f) / vec2(textureSize(Sampler2, 0)));
    return bit == 1 ? t.r : bit == 2 ? t.g : t.b;
}

// The same for the two sizes drawn, blended in the cover itself (not in the picture), so blocks
// grow and merge into the bigger ones instead of fading over them.
float cloudShape(vec2 p, int bit) {
    float v = cloudRaw(p, cloudSize, bit);
    return cloudGrow > 0.0 ? mix(v, cloudRaw(p, cloudSize + 1, bit), cloudGrow) : v;
}

// The layer's cover with a clean edge, anti-aliased by the pixel's size.
float cloudCover(vec2 p, int bit) {
    float aa = clamp(pixelUnits / (cloudBase * exp2(float(cloudSize) + cloudGrow)), 0.01, 0.5);
    return smoothstep(0.48 - aa, 0.52 + aa, cloudShape(p, bit));
}

// The big masses' height at p: the noise at half and the whole of the wrap the map point repeats
// over, blended by how far into the zoom step the view is, so it never jumps where the point wraps
// and grows smoothly from one step into the next (the half at the next step is this one's whole).
float bigHeight(vec2 p, float wrap) {
    float fine = texture(Sampler1, p * 2.0 / wrap + vec2(0.41, 0.27)).r;
    float coarse = texture(Sampler1, p / wrap + vec2(0.41, 0.27)).r;
    return mix(fine, coarse, scaleFraction);
}

// The clouds over a ground point: premultiplied colour, and how much shows through. The layers from
// the top down, each the first surface the line of sight meets there: its top, or its south wall.
vec4 clouds(vec2 p0) {
    float size = cloudBase * exp2(float(cloudSize) + cloudGrow);
    float thick = size * CLOUD_THICK;
    vec2 sunward = normalize(vec2(-0.5, -0.8));
    float ramp = CLOUD_RAMP * min(1.0, inversesqrt(size / CLOUD_CELL));
    float big = mix(1.0, mix(0.92, 1.03, bigLight), bigShare);
    // Which layers any cell within reach has, and whether all of them are the deck alone.
    ivec2 cell = clamp(ivec2(floor(p0 / (cloudBase * float(gridMult)))) - cellFirst, ivec2(0), textureSize(Sampler2, 0) - 1);
    int near = int(floor(texelFetch(Sampler2, cell, 0).a * 255.0 + 0.5));
    int small = near % 16;
    int large = near / 16;
    int bits = cloudSize >= 2 ? large : cloudSize == 1 ? ((small | large) % 8) | (small & large & 8) : small;
    if (bits % 8 == 0) {
        return vec4(0.0, 0.0, 0.0, 1.0);
    }
    if (bits >= 8 && bits % 8 == 1) {
        // Deep in the deck with nothing over it: its plain top.
        return vec4(CLOUD_TOP * big, 0.0);
    }
    // Walls under ten pixels tall need two reads, not three.
    bool tall = thick * COS_PITCH / pixelUnits >= 10.0;
    vec3 glow = vec3(0.0);
    float through = 1.0;
    for (int k = 3; k >= 1; k--) {
        int bit = k == 3 ? 4 : k == 2 ? 2 : 1;
        if (bits / bit % 2 == 0) {
            continue;
        }
        float top = float(k) * thick;
        vec2 pt = vec2(p0.x, p0.y + top * COS_PITCH);
        float face = cloudCover(pt, bit);
        // Under a whole top the wall can't show: no need to read it.
        float w1 = face;
        float w2 = face;
        float w3 = face;
        if (face < 0.999) {
            w1 = cloudCover(vec2(p0.x, p0.y + (top - (tall ? 0.33 : 0.5) * thick) * COS_PITCH), bit);
            w2 = tall ? cloudCover(vec2(p0.x, p0.y + (top - 0.66 * thick) * COS_PITCH), bit) : w1;
            w3 = cloudCover(vec2(p0.x, p0.y + (top - 0.97 * thick) * COS_PITCH), bit);
        }
        float a1 = max(w1 - face, 0.0);
        float a2 = max(w2 - max(face, w1), 0.0);
        float a3 = max(w3 - max(max(face, w1), w2), 0.0);
        float a = min(face + a1 + a2 + a3, 1.0);
        if (a <= 0.001) {
            continue;
        }
        // Soft shadows from the layers above, cast away from the sun.
        float shaded = 0.0;
        if (k < 3 && !fast && face > 0.001) {
            for (int u = k + 1; u <= 3; u++) {
                float lift = float(u - k) * thick;
                shaded = max(shaded, cloudShape(vec2(pt.x, pt.y + lift * COS_PITCH) + sunward * lift * 1.2, u == 3 ? 4 : 2));
            }
            shaded = smoothstep(0.15, 0.85, shaded) * (1.0 - cloudCover(vec2(p0.x, p0.y + (top + thick) * COS_PITCH), k == 2 ? 4 : 2));
        }
        vec3 topColor = mix(CLOUD_TOP, CLOUD_SHADED, 0.8 * shaded) * big;
        vec3 sides = a1 * mix(CLOUD_SIDE_LOW, CLOUD_SIDE_HIGH, 0.85) + a2 * mix(CLOUD_SIDE_LOW, CLOUD_SIDE_HIGH, 0.5) + a3 * mix(CLOUD_SIDE_LOW, CLOUD_SIDE_HIGH, 0.15);
        vec3 color = (face * topColor + sides * mix(1.0, big, 0.5)) / max(face + a1 + a2 + a3, 1e-4);
        // The middle layer is see-through over explored land, more solid toward the unexplored.
        float alpha = 1.0;
        if (k == 2) {
            float s = signedAt(pt);
            alpha = mix(mix(0.5, 0.75, clamp((s + ramp) / ramp, 0.0, 1.0)), 1.0, smoothstep(-4.0, 6.0, s));
        }
        alpha *= a;
        glow += through * alpha * color;
        through *= 1.0 - alpha;
        if (through < 0.01) {
            break;
        }
    }
    // The see-through layer's soft shadow on the land under it.
    if (!fast && through > 0.01) {
        vec2 q = vec2(p0.x, p0.y + 2.0 * thick * COS_PITCH) + sunward * 2.0 * thick * 0.6;
        float s = signedAt(p0);
        through *= 1.0 - GROUND_SHADOW * smoothstep(0.1, 0.9, cloudShape(q, 2)) * mix(0.5, 0.75, clamp((s + ramp) / ramp, 0.0, 1.0));
    }
    return vec4(glow, through);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// A random number for the map square of side q that holds p. It repeats every 1024 map units,
// which every jump of the map point (a multiple of the noise's coarse period) is a whole number of.
float squareHash(vec2 p, float q) {
    return hash(mod(floor(p / q), max(2.0 * NOISE_PERIOD / q, 1.0)));
}

// The march's jitter, -0.5 to 0.5, from the map point rather than the screen pixel, so it stays
// with the map as the view pans and zooms: from the screen's pixels, the clouds' thin wisps were
// hit by different steps of the march each time the map moved, and changed shape. Squares of the
// two powers of two around a pixel's size are blended by where the pixel falls between them, so
// zooming changes it smoothly; at each power of two it is that size's alone. The blend is
// scaled back to the spread of one square's number.
float mapJitter(vec2 p, vec2 pixel) {
    float size = log2(max(max(pixel.x, pixel.y), 1e-4));
    float q = exp2(floor(size));
    float t = size - floor(size);
    float j = mix(squareHash(p, q), squareHash(p, q * 2.0), t) - 0.5;
    return clamp(j / length(vec2(1.0 - t, t)), -0.5, 0.5);
}

void main() {
    seconds = GameTime * 1200.0;
    // The red byte is the noise offset in its top three bits and, in its low five, how see-through
    // the whole fog is, in 31sts: 0 draws the fog as it is, and the minimap raises it to fade the map.
    vec2 offsetBytes = floor(params.rg * 255.0 + 0.5);
    noiseOrigin = floor(offsetBytes / 32.0) / 8.0;
    float fogOpacity = 1.0 - mod(offsetBytes.r, 32.0) / 31.0;
    // The green byte's low five bits are how far into the night the map is drawn, in 31sts (NightLook).
    float night = mod(offsetBytes.g, 32.0) / 31.0;
    float alphaCode = floor(params.a * 255.0 + 0.5);
    unitsPerUv = MASK_TEXELS * 2.0 * exp2(floor(alphaCode / 16.0));
    // In the low nibble: 4 for Fast, 1 for smoke, 2 into the half-resolution layer (FogLayer).
    fast = mod(floor(mod(alphaCode, 16.0) / 4.0), 2.0) >= 1.0;
    bool layer = mod(floor(mod(alphaCode, 16.0) / 2.0), 2.0) >= 1.0;
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
    deep = flags.b > 0.5;

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
    // The map point under this pixel, and its jitter.
    originMap = noiseOrigin * NOISE_PERIOD * exp2(scaleSteps + 1.0);
    vec2 p0 = originMap + maskUv * unitsPerUv;
    float jitter = mapJitter(p0, fwidth(p0));
    pixelUnits = max(fwidth(p0).x, fwidth(p0).y);

    vec3 moonlit = mix(vec3(1.0), vec3(0.27, 0.33, 0.49), night);
    if (!cave && !smoke) {
        // The noise origin is ten base cells from map 0 for each eighth of the coarse noise period it
        // moves. Past the middle of a zoom step the grid's cells are two base cells (MapViewport.CellGrid).
        cloudBase = CLOUD_CELL * exp2(scaleSteps);
        gridMult = scaleFraction >= 0.58 ? 2 : 1;
        ivec2 originCells = ivec2(floor(offsetBytes / 32.0));
        cellFirst = originCells * (10 / gridMult) - CELL_MARGIN;
        // At (0, 0): 255 less 16 for each size under the smallest drawn, and 8 for the large layout.
        float ready = texelFetch(Sampler2, ivec2(0), 0).a;
        cellsReady = ready > 0.5;
        int cloudLayout = int(floor((1.0 - ready) * 255.0 + 0.5));
        cloudSmallest = cloudLayout / 16;
        cloudsLarge = cloudLayout % 16 >= 8;
        // Cells about CLOUD_PIXELS screen pixels across (a layer texel is two), a power of two of the
        // base, the size growing into the next through the middle of each zoom step.
        float screen = layer ? pixelUnits * 0.5 : pixelUnits;
        float size = log2(CLOUD_PIXELS * screen / cloudBase);
        size = clamp(size, float(cloudSmallest), float(CLOUD_SIZES - 1));
        cloudSize = min(int(floor(size)), CLOUD_SIZES - 1);
        cloudGrow = cloudSize >= CLOUD_SIZES - 1 ? 0.0 : smoothstep(0.2, 0.8, size - float(cloudSize));
        // Grown all the way: the next size alone.
        if (cloudGrow >= 1.0) {
            cloudSize++;
            cloudGrow = 0.0;
        }
        // Zoomed out, a slow lit height field groups the clouds into big masses (VanillaClouds gathers
        // the top layer by the same idea).
        float zoomedOut = log2(CLOUD_PIXELS * screen / CLOUD_CELL);
        bigShare = smoothstep(-0.6, 1.0, zoomedOut);
        if (bigShare > 0.0 && !fast) {
            float wrap = NOISE_PERIOD * exp2(scaleSteps + 1.0);
            float e = wrap / 48.0;
            float h0 = bigHeight(p0, wrap);
            vec3 n = normalize(vec3(-(bigHeight(p0 + vec2(e, 0.0), wrap) - h0) * 9.0, -(bigHeight(p0 + vec2(0.0, e), wrap) - h0) * 9.0, 1.0));
            bigLight = clamp(dot(n, normalize(vec3(-0.45, -0.55, 0.7))) * 1.1 - 0.2 + 0.8 * (h0 - 0.5), 0.0, 1.0);
        }
        vec4 c = cellsReady ? clouds(p0) : vec4(0.0, 0.0, 0.0, 1.0);
        // Under the clouds: a remembered map (from before a turn) not yet redrawn lies under a soft
        // cover in the clouds' colour, thinnest beside the land already redrawn, and goes with the
        // eased heights, so redrawn land fades in instead of appearing a tile at a time. Ground with
        // no map at all, never explored, is never seen.
        float knownHere = knownAt(maskUv);
        float notDrawn = 1.0 - smoothstep(0.3, 0.85, knownHere);
        float remembered = clear ? 0.0 : clamp(texture(Sampler0, maskUv).g / 0.4, 0.0, 1.0);
        float cover = mix(notDrawn, smoothstep(0.0, 0.5, liftHere) * mix(1.0 - MEMORY_SEE, 1.0, smoothstep(0.5, 1.0, liftHere)), remembered);
        c.rgb += c.a * cover * CLOUD_TOP;
        c.a *= 1.0 - cover;
        float alpha = 1.0 - c.a;
        if (alpha <= 0.002) {
            if (!layer) {
                discard;
            }
            fragColor = vec4(CLOUD_TOP * moonlit, 0.0);
            return;
        }
        fragColor = vec4(c.rgb / alpha * moonlit, alpha * fogOpacity) * ColorModulator;
        return;
    }

    // The known share right here, sharp and soft, read once.
    float knownHere = knownAt(maskUv);
    float soft = knownSoft(maskUv);

    vec3 color = vec3(0.0);
    float alpha = 0.0;
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
        float light = clamp(0.2 + 0.7 * (h / fogHeight) + 0.3 - occlusion * FOG_SHADOW, 0.0, 1.0);
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
    // Low fog over a remembered map keeps the old map under it.
    float floorFog = smoothstep(0.5, 2.5, thicknessAt(maskUv, soft)) * smoothstep(0.3, 0.9, liftHere) * (1.0 - memory);
    color += (1.0 - alpha) * floorFog * shade;
    alpha += (1.0 - alpha) * floorFog;

    // Ground that is known right here stays visible; fog only rolls over its edges. Caves use the
    // sharp mask so narrow tunnels stay clear; the surface uses the blurred one so banks stay soft.
    float here = cave ? knownHere : soft;
    alpha *= 1.0 - 0.85 * smoothstep(0.45, 0.95, here);

    // Where there is no map at all, nothing should show through, or the edge of the data would
    // read as a hard line inside the bank.
    // The cover takes the colour the layers already have here, so it doesn't show as a band.
    // A remembered map is only covered up as its fog reaches the full height.
    float empty = (1.0 - smoothstep(0.3, 0.85, knownHere)) * mix(MEMORY_COVER, 1.0, smoothstep(0.7, 1.0, liftHere)) * (1.0 - memory);
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

    if (alpha <= 0.002) {
        if (!layer) {
            discard;
        }
        // The layer is shown scaled up, filtered between its texels: an empty one keeps a fog
        // colour, so the fog's edge blends toward that rather than toward black.
        fragColor = vec4(mix(shade, lit, 0.7) * moonlit, 0.0);
        return;
    }
    color += jitter / 255.0;
    fragColor = vec4(color / alpha * moonlit, alpha * fogOpacity) * ColorModulator;
}
