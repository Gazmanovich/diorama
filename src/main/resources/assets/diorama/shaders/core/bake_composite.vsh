#version 330
#extension GL_ARB_separate_shader_objects : require

// One triangle over the whole target, like vanilla's screenquad.
void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
