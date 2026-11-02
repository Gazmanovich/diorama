#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
//?}

// A map tile shown no larger than its pixels are crisp at. The GPU's own filter blurs here: it
// blends two mip levels and reads each between its pixels, so a map shrunk to fit looks soft. This
// gives each screen pixel the exact average of the tile pixels under it instead, from a level
// fine enough for the average to fall where the pixel does. Averages don't sparkle while panning,
// as sharpening or reading a finer level with a single tap would.

// A tile with its mip levels, drawn with linear filtering.
uniform sampler2D Sampler0;

// From the fog's vertex shader, which passes both through: the place on the tile, and its tint.
//? if >=26.3 {
layout(location = 0) in vec2 maskUv;
layout(location = 1) in vec4 params;

layout(location = 0) out vec4 fragColor;
//?} else {
/*in vec2 maskUv;
in vec4 params;

out vec4 fragColor;
*///?}

const float LAST_LEVEL = 5.0;

// One axis of the average, over a screen pixel "width" texels wide around "at", which touches at
// most five texels: three linear reads at the returned places, each weighing the two texels it
// falls between as much of the pixel lies on them. Their weights go in "weights".
vec3 reads(float at, float width, out vec3 weights) {
    float lo = at - width * 0.5;
    float hi = at + width * 0.5;
    float first = floor(lo);
    float t[5];
    for (int i = 0; i < 5; i++) {
        t[i] = max(min(first + float(i) + 1.0, hi) - max(first + float(i), lo), 0.0);
    }
    weights = vec3(t[0] + t[1], t[2] + t[3], t[4]) / width;
    return vec3(
        first + 0.5 + t[1] / max(t[0] + t[1], 1e-6),
        first + 2.5 + t[3] / max(t[2] + t[3], 1e-6),
        first + 4.5
    );
}

void main() {
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 place = maskUv * size;
    // How many of the tile's pixels one screen pixel spans each way.
    vec2 span = vec2(length(vec2(dFdx(place.x), dFdy(place.x))), length(vec2(dFdx(place.y), dFdy(place.y))));
    // Two to four texels of the level read, where the tile is shrunk by that much: reading a level
    // whose texels are as big as the screen pixel blurs wherever they don't line up.
    float level = clamp(floor(log2(max(max(span.x, span.y), 1.0))) - 1.0, 0.0, LAST_LEVEL);
    float scale = pow(2.0, level);
    vec2 texels = size / scale;
    vec3 weightsX;
    vec3 weightsY;
    vec3 x = reads(place.x / scale, span.x / scale, weightsX);
    vec3 y = reads(place.y / scale, span.y / scale, weightsY);
    vec4 color = vec4(0.0);
    for (int j = 0; j < 3; j++) {
        for (int i = 0; i < 3; i++) {
            float weight = weightsX[i] * weightsY[j];
            if (weight > 0.0) {
                color += textureLod(Sampler0, vec2(x[i], y[j]) / texels, level) * weight;
            }
        }
    }
    color *= params;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color;
}
