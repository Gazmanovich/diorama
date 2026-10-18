#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
//?} else if >=1.21.11 {
/*
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
*///?} else if >=1.21.6 {
/*// Must match fog.fsh's block, which before 1.21.11 ends with a line width.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};
*///?}
//? if >=1.21.6 {
layout(std140) uniform Projection {
    mat4 ProjMat;
};
//?} else {
/*// Before 1.21.6 uniforms were set one by one.
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
*///?}

//? if >=26.3 {
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 maskUv;
layout(location = 1) out vec4 params;
//?} else {
/*in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 maskUv;
out vec4 params;
*///?}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    maskUv = UV0;
    params = Color;
}
