$input v_texcoord0
#include <bgfx_shader.sh>

SAMPLER2D(s_ColorTexture, 1);
SAMPLER2D(s_RasterizedColor, 5);

float getLL(vec3 color){
    return dot(color, vec3(0.2125, 0.7154, 0.0721));
}

vec3 cSatur(vec3 color, float sat){
    float lum = getLL(color);
    return mix(vec3_splat(lum), color, sat);
}

vec3 filmic(vec3 x){
    x = max(0.0, x);
    return (x * (6.2 * x + 0.5)) / (x * (6.2 * x + 1.7) + 0.06);
}

void main(){
    vec3 finalColor = texture2D(s_ColorTexture, v_texcoord0).rgb;
        finalColor *= 0.0001;
        finalColor = cSatur(finalColor, 1.1);
        finalColor = filmic(finalColor);
        finalColor = clamp(finalColor, 0.0, 1.0);

    vec4 rasterized = texture2D(s_RasterizedColor, v_texcoord0);
        finalColor *= 1.0 - rasterized.a;
        finalColor += rasterized.rgb;
    gl_FragColor = vec4(finalColor, 1.0);
}