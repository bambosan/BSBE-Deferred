$input a_position
#include <bgfx_shader.sh>

void main() {
#if FALLBACK
    gl_Position = vec4(0.0, 0.0, 0.0, 0.0);
#else
    gl_Position = mul(u_modelViewProj, vec4(a_position, 1.0));
#endif
}
