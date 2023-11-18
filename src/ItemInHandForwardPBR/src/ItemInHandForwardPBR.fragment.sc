$input v_texcoord0, v_color0, v_light, v_fog, v_normal, v_worldPos
#include <bgfx_shader.sh>
#include <bgfx_compute.sh>
#include <MinecraftRenderer.Materials/DynamicUtil.dragonh>
#include <MinecraftRenderer.Materials/FogUtil.dragonh>

uniform vec4 ChangeColor;
uniform vec4 OverlayColor;
uniform vec4 ColorBased;
uniform vec4 MultiplicativeTintColor;

#if defined(FORWARD_PBR_TRANSPARENT) || defined(FORWARD_PBR_ALPHA_TEST) || defined(FORWARD_PBR_OPAQUE)
uniform vec4 SunDir;
uniform vec4 MoonDir;
uniform vec4 ShadowParams;
uniform vec4 ShadowBias;
uniform vec4 ShadowSlopeBias;
uniform vec4 CascadeShadowResolutions;
uniform vec4 CameraLightIntensity;
uniform vec4 DirectionalLightToggleAndCountAndMaxDistance;
uniform vec4 FogColor;
uniform vec4 FogAndDistanceControl;
uniform vec4 TileLightIntensity;

SAMPLER2DARRAYSHADOW(s_ShadowCascades0, 7);
SAMPLER2DARRAYSHADOW(s_ShadowCascades1, 8);

struct LightSourceWorldInfo {
    vec4 worldSpaceDirection;
    vec4 diffuseColorAndIlluminance;
    vec4 shadowDirection;
    mat4 shadowProj0;
    mat4 shadowProj1;
    mat4 shadowProj2;
    mat4 shadowProj3;
    int isSun;
    int shadowCascadeNumber;
    int pad0;
    int pad1;
};

BUFFER_RO(s_DirectionalLightSources, LightSourceWorldInfo, 2);

float getLL(vec3 color){
    return dot(color, vec3(0.2125, 0.7154, 0.0721));
}

vec3 cSatur(vec3 color, float sat){
    float lum = getLL(color);
    return mix(vec3_splat(lum), color, sat);
}

float getMie(vec3 lPos, vec3 pos){
    return exp(-distance(pos, lPos) * 2.0) * exp(-saturate(pos.y) * 4.0);
}

float getMie1(vec3 lPos, vec3 pos){
    return exp(-distance(pos, lPos) * 2.0);
}
#endif

void main() {
    #if DEPTH_ONLY
        gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
        return;
    #endif

    vec4 albedo;
    albedo.rgb = mix(vec3(1.0, 1.0, 1.0), v_color0.rgb, ColorBased.x);
    albedo.a = 1.0;

    #if MULTI_COLOR_TINT
        albedo = applyMultiColorChange(albedo, ChangeColor.rgb, MultiplicativeTintColor.rgb);
    #else
        albedo = applyColorChange(albedo, ChangeColor, v_color0.a);
    #endif

    albedo = applyOverlayColor(albedo, OverlayColor);
    //albedo = applyLighting(albedo, v_light);

    #if ALPHA_TEST
        if (albedo.a < 0.5) {
            discard;
        }
    #endif
    
    albedo.rgb = pow(albedo.rgb, vec3_splat(2.2));

    #if defined(FORWARD_PBR_TRANSPARENT) || defined(FORWARD_PBR_ALPHA_TEST) || defined(FORWARD_PBR_OPAQUE)
        vec4 vPos = mul(u_view, vec4(v_worldPos, 1.0));
        vec3 nWP = normalize(v_worldPos);
        vec3 wNor = normalize(v_normal);
        float lVis = 1.0;
        float rain = 1.0 - smoothstep(0.3, 0.7, FogAndDistanceControl.x);

        if(int(DirectionalLightToggleAndCountAndMaxDistance.y) != 0){
            LightSourceWorldInfo ld = s_DirectionalLightSources[0];
            vec4 shP;
            int csIdx = -1;
            mat4 csProj[4] = { ld.shadowProj0, ld.shadowProj1, ld.shadowProj2, ld.shadowProj3 };
            for(int j = 0; j < 4; j++){
                shP = mul(csProj[j], vec4(v_worldPos.xyz, 1.0));
                shP /= shP.w;
                if(length(clamp(shP.xyz, -1.0, 1.0) - shP.xyz) == 0.0){
                    csIdx = j;
                    break;
                }
            }

            if(csIdx != -1){
                float bias = 0.0001 + 0.00042 * saturate(tan(acos(max(dot(wNor, normalize(ld.shadowDirection.xyz)), 0.0))));
                shP.z -= bias / shP.w;
                shP.y *= -1.0;
                shP.xy = shP.xy * 0.5 + 0.5;

                vec2 pDO[4] = { vec2(1.0, 1.0), vec2(-1.0, 1.0), vec2(1.0, -1.0), vec2(-1.0, -1.0) };
                float fSmap = 0.0;
                for(int i = 0; i < 4; i++){
                    vec2 osp = (shP.xy + (pDO[i] * 0.0001)) * CascadeShadowResolutions[csIdx];
                    if(ld.shadowCascadeNumber == 0){
                        fSmap += shadow2DArray(s_ShadowCascades0, vec4(osp, float(csIdx), shP.z)).r;
                    } else if(ld.shadowCascadeNumber == 1){
                        fSmap += shadow2DArray(s_ShadowCascades1, vec4(osp, float(csIdx), shP.z)).r;
                    }
                }
                lVis = saturate(fSmap * 0.25);
                lVis = mix(lVis, 1.0, smoothstep(max(0.0, ShadowParams.y - 8.0), ShadowParams.y, -vPos.z));
                lVis *= saturate(dot(wNor, normalize(ld.worldSpaceDirection.xyz)));
                lVis = saturate(lVis - rain);
            }

            vec3 sunP = normalize(SunDir.xyz);
            float sunH = saturate(sunP.y);
            vec3 moonP = normalize(MoonDir.xyz);
            float moonH = saturate(moonP.y);

            vec3 linFogC = pow(FogColor.rgb, vec3_splat(2.2));
            vec3 sunC = mix(vec3((1.0 - sunH) * 0.5 + sunH, sunH, sunH * sunH) * sunH, linFogC, rain);
            vec3 moonC = mix(vec3(moonH * 0.03, moonH * 0.06, moonH * 0.1) * moonH, linFogC, rain);
            vec3 horC = cSatur(moonC + sunC, 0.5);
            vec3 zenC = mix(vec3(0.0, sunH * 0.05 + 0.01, sunH * 0.5 + 0.05), linFogC, rain);
            // ambient - albedo
            albedo.rgb *= (vec3_splat(0.01) + (cSatur(zenC, 0.3) * (2.0 - rain * 1.5) * TileLightIntensity.y) + vec3(1.0, 0.6, 0.3) * ((TileLightIntensity.x * TileLightIntensity.x) * 0.2 + pow(TileLightIntensity.x, 16.0) * 5.0) + (moonC + sunC) * lVis * 2.0);
            // near fog
            albedo.rgb = mix(albedo.rgb, zenC * (3.0 - sunH * 0.5), 1.0 - exp(-saturate(length(-v_worldPos.xyz) * 0.01) * max(0.05 - sunH * 0.02, rain * 0.1) * CameraLightIntensity.y));

            vec3 bFogC = mix(zenC, horC, exp(-saturate(nWP.y) * 2.0) * 0.1) + (sunC * getMie(sunP, nWP) * 4.0) + (moonC * getMie(moonP, nWP));
            albedo.rgb = mix(albedo.rgb, bFogC, v_fog.a);
            albedo.rgb *= vec3(2.0, 1.9, 1.8);
            albedo.rgb *= (1.0 - (sunH + moonH) * 0.8);
        } else {
            albedo.rgb *= (vec3_splat(0.03) + vec3(1.0, 0.6, 0.3) * ((TileLightIntensity.x * TileLightIntensity.x) * 0.2 + pow(TileLightIntensity.x, 16.0) * 5.0));
        }
    #endif
    albedo.rgb = max(vec3_splat(0.0), albedo.rgb * 1000.0);
    gl_FragColor = albedo;
}
