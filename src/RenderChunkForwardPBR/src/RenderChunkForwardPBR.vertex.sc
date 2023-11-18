$input a_color0, a_position, a_texcoord0, a_texcoord1, a_normal
#ifdef INSTANCING
    $input i_data0, i_data1, i_data2
#endif
$output v_color0, v_normal, v_texcoord0, v_lightmapUV, v_worldPos

#include <bgfx_shader.sh>
#include <MinecraftRenderer.Materials/TAAUtil.dragonh>
#include <MinecraftRenderer.Materials/FogUtil.dragonh>

uniform vec4 FogAndDistanceControl;
uniform vec4 FogColor;
uniform vec4 ViewPositionAndTime;
uniform vec4 RenderChunkFogAlpha;

void main() {
    vec3 worldPosition;
#ifdef INSTANCING
    mat4 model;
    model[0] = vec4(i_data0.x, i_data1.x, i_data2.x, 0);
    model[1] = vec4(i_data0.y, i_data1.y, i_data2.y, 0);
    model[2] = vec4(i_data0.z, i_data1.z, i_data2.z, 0);
    model[3] = vec4(i_data0.w, i_data1.w, i_data2.w, 1);
    worldPosition = instMul(model, vec4(a_position, 1.0)).xyz;
#else
    worldPosition = mul(u_model[0], vec4(a_position, 1.0)).xyz;
#endif

    vec3 normal = vec3(0.0, 0.0, 0.0);
#if FORWARD_PBR_TRANSPARENT
    normal = mul(u_model[0], vec4(a_normal.xyz, 0.0)).xyz;
#endif

    v_texcoord0 = a_texcoord0;
    v_color0 = a_color0;
    v_lightmapUV = a_texcoord1;
    v_normal = normal;
    v_worldPos = worldPosition;
    gl_Position = jitterVertexPosition(worldPosition);
}
