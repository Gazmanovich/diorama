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

// One triangle over the whole target, like vanilla's screenquad.
void main() {
    vec2 uv = vec2((VERTEX_INDEX << 1) & 2, VERTEX_INDEX & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
