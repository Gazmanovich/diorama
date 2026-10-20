#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require

#include <diorama:bake.glsl>
//?} else {
/*#moj_import <diorama:bake.glsl>
*///?}

// One triangle over the whole target, like vanilla's screenquad.
void main() {
    vec2 uv = vec2((VERTEX_INDEX << 1) & 2, VERTEX_INDEX & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
