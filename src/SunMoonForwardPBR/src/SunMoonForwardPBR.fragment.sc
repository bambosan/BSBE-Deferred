$input v_ndcPosition, v_texcoord0
#include <bgfx_shader.sh>

void main() {
#if FALLBACK
    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
#else
    discard;
#endif
}
