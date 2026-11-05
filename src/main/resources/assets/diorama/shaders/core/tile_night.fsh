#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
//?}

// A map tile at night that has block light on it: the day picture dimmed toward moonlight, with a
// warm glow over it where torches, lamps and lava light the ground. Tiles with no light at night
// are drawn by the usual shaders, tinted by NightLook.dim on the CPU, which is this with no light.
// The numbers here are NightLook's; change them together.

// The tile's day picture with its mip levels.
uniform sampler2D Sampler0;
// Its block light (BlockLight), a value per block in red, linear with mip levels.
uniform sampler2D Sampler1;

// From the fog's vertex shader: the place on the tile, and the vertex colour, which here carries
// how far into the night it is in red (0 day to 1 night), 1 in green to keep magnified pixels
// crisp (else each screen pixel averages the tile's under it, as tile.fsh does), and the map's
// opacity in alpha.
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
const vec3 MOON = vec3(0.27, 0.33, 0.49);
const vec3 WARM = vec3(1.15, 0.82, 0.50);
const float GLOW = 1.35;
const float GLOW_CURVE = 0.9;

// As tile.fsh: one axis of the area average over a screen pixel "width" texels wide around "at".
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

// As tile.fsh: the average of the area of the tile under this screen pixel.
vec4 area() {
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 place = maskUv * size;
    vec2 span = vec2(length(vec2(dFdx(place.x), dFdy(place.x))), length(vec2(dFdx(place.y), dFdy(place.y))));
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
    return color;
}

void main() {
    vec4 color = params.g > 0.5 ? texture(Sampler0, maskUv) : area();
    float night = params.r;
    float light = texture(Sampler1, maskUv).r;
    vec3 lit = mix(vec3(1.0), MOON, night) + WARM * (pow(light, GLOW_CURVE) * GLOW * night);
    color.rgb = min(color.rgb * lit, vec3(1.0));
    color.a *= params.a;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color;
}
