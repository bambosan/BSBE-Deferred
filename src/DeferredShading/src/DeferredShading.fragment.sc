$input v_projPosition, v_texcoord0

#include <bgfx_shader.sh>
#include <bgfx_compute.sh>

#if !defined(FALLBACK) && (BGFX_SHADER_LANGUAGE_GLSL >= 310 || BGFX_SHADER_LANGUAGE_HLSL >= 500 || BGFX_SHADER_LANGUAGE_PSSL || BGFX_SHADER_LANGUAGE_SPIRV || BGFX_SHADER_LANGUAGE_METAL)

uniform vec4 SunDir;
uniform vec4 MoonDir;

uniform vec4 ShadowPCFWidth;
uniform vec4 ShadowParams;
uniform vec4 ShadowBias;
uniform vec4 ShadowSlopeBias;
uniform vec4 CascadeShadowResolutions;
uniform vec4 DirectionalLightToggleAndCountAndMaxDistance;
uniform vec4 BlockBaseAmbientLightColorIntensity;
uniform vec4 SkyAmbientLightColorIntensity;

uniform vec4 PrepassUVOffset;
uniform vec4 FogColor;
uniform vec4 FogAndDistanceControl;
uniform vec4 ViewPositionAndTime;

SAMPLER2D(s_ColorMetalness, 1);
SAMPLER2D(s_SceneDepth, 0);
SAMPLER2D(s_Normal, 2);
SAMPLER2D(s_EmissiveAmbientLinearRoughness, 3);

SAMPLER2DARRAYSHADOW(s_ShadowCascades0, 7);
SAMPLER2DARRAYSHADOW(s_ShadowCascades1, 9);

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

BUFFER_RO(s_DirectionalLightSources, LightSourceWorldInfo, 8);

float Bayer2(vec2 a) {
    a = floor(a);
    return fract(dot(a, vec2(0.5, a.y * 0.75)));
}

#define Bayer4(a) (Bayer2(0.5 * (a)) * 0.25 + Bayer2(a))
#define Bayer8(a) (Bayer4(0.5 * (a)) * 0.25 + Bayer2(a))
#define Bayer16(a) (Bayer8(0.5 * (a)) * 0.25 + Bayer2(a))
#define Bayer32(a) (Bayer16(0.5 * (a)) * 0.25 + Bayer2(a))
#define Bayer64(a) (Bayer32(0.5 * (a)) * 0.25 + Bayer2(a))

float getLL(vec3 color){
    return dot(color, vec3(0.2125, 0.7154, 0.0721));
}

vec3 cSatur(vec3 color, float sat){
    float lum = getLL(color);
    return mix(vec3_splat(lum), color, sat);
}

vec3 getSunC(float sunH){
    sunH = pow(saturate(sunH), 0.7);
    return vec3((1.0 - sunH) * 0.5 + sunH, sunH, sunH * sunH) * sunH;
}

vec3 getMoonC(float MoonH){
    return vec3_splat(pow(saturate(MoonH), 0.7)) * vec3(0.0, 0.05, 0.2);
}

vec3 getZenC(float sunH){
    sunH = pow(saturate(sunH), 0.6);
    return vec3(0.0, sunH * 0.05 + 0.01, sunH * 0.5 + 0.05);
}

float getMie(vec3 lPos, vec3 pos){
    return exp(-distance(pos, lPos) * 2.0) * exp(-saturate(pos.y) * 4.0);
}

float getMie1(vec3 lPos, vec3 pos){
    return exp(-distance(pos, lPos) * 2.0);
}

float hash(float h){
    return fract(sin(h) * 43758.5453);
}

float voronoi2d(vec2 pos){
    vec2 p = floor(pos);
    vec2 f = fract(pos);
    float dist = 1.0;
    for(float y = -1.0; y <= 1.0; y++){
        for(float x = -1.0; x <= 1.0; x++){
            vec2 ne = vec2(x, y);
            vec2 pn = p + ne;
            float n = pn.x + pn.y * 57.0;
            dist = min(dist, length(ne + hash(n) - f));
        }
    }
    return dist;
}

float fbm(vec2 pos, float pDens, float fTime){
    float sum = 0.0;
    float sDens = 1.0;
    pos += fTime * 0.001;
    for(int i = 0; i < 4; i++){
        sum += voronoi2d(pos) * sDens * pDens;
        sDens *= 0.5;
        pos *= 2.5;
        pos += fTime * 0.05;
    }
    return saturate(1.0 - sum);
}

vec4 projToView(vec4 p, mat4 inverseProj) {
#if BGFX_SHADER_LANGUAGE_GLSL
    p = vec4(
        p.x * inverseProj[0][0],
        p.y * inverseProj[1][1],
        p.w * inverseProj[3][2],
        p.z * inverseProj[2][3] + p.w * inverseProj[3][3]);
#else
    p = vec4(
        p.x * inverseProj[0][0],
        p.y * inverseProj[1][1],
        p.w * inverseProj[2][3],
        p.z * inverseProj[3][2] + p.w * inverseProj[3][3]);
#endif
    p /= p.w;
    return p;
}

vec2 octWrap(vec2 v) {
    return (1.0 - abs(v.yx)) * ((2.0 * step(0.0, v)) - 1.0);
}

vec3 octToNdirSnorm(vec2 p) {
    vec3 n = vec3(p.xy, 1.0 - abs(p.x) - abs(p.y));
    n.xy = (n.z < 0.0) ? octWrap(n.xy) : n.xy;
    return normalize(n);
}

float cdist(vec2 coord){
	return clamp(1.0 - max(abs(coord.x - 0.5), abs(coord.y - 0.5)) * 2.0, 0.0, 1.0);
}

float calculateFogIntensity(float cameraDepth, float maxDistance, float fogStart, float fogEnd){
    float dist = cameraDepth / maxDistance;
    return saturate((dist - fogStart) / (fogEnd - fogStart));
}
#endif

void main() {
#if !defined(FALLBACK) && (BGFX_SHADER_LANGUAGE_GLSL >= 310 || BGFX_SHADER_LANGUAGE_HLSL >= 500 || BGFX_SHADER_LANGUAGE_PSSL || BGFX_SHADER_LANGUAGE_SPIRV || BGFX_SHADER_LANGUAGE_METAL)
    vec2 uv = vec2(v_texcoord0.x * PrepassUVOffset.x + PrepassUVOffset.y, v_texcoord0.y);
    float z = texture2D(s_SceneDepth, uv).r;
    #if BGFX_SHADER_LANGUAGE_GLSL
        z = z * 2.0 - 1.0;
    #endif
    vec4 vPos = projToView(vec4(v_projPosition.xy, z, 1.0), u_invProj);
    vec4 wPos = mul(u_invView, vec4(vPos.xyz, 1.0));
    vec3 nWP = normalize(wPos.xyz);
    vec2 rNor = texture2D(s_Normal, uv).xy;
    vec3 wNor = normalize(octToNdirSnorm(rNor));
    vec3 vNor = normalize(mul(u_view, vec4(wNor, 0.0)).xyz);
    vec4 cm = texture2D(s_ColorMetalness, uv);
    vec4 ear = texture2D(s_EmissiveAmbientLinearRoughness, uv);
    vec3 outCol = pow(cm.rgb, vec3_splat(2.2));

    float lVis = 1.0;
    float rain = smoothstep(0.1, 0.4, FogAndDistanceControl.x) - smoothstep(0.4, 0.6, FogAndDistanceControl.x);

    vec3 sunP = normalize(SunDir.xyz);
    vec3 MoonP = normalize(MoonDir.xyz);
    vec3 linFogC = pow(FogColor.rgb, vec3_splat(2.2));
    vec3 sunC = getSunC(sunP.y);
        sunC = mix(sunC, linFogC, rain);
    vec3 moonC = getMoonC(MoonP.y);
        moonC = mix(moonC, linFogC, rain);
    vec3 zenC = getZenC(sunP.y);
        zenC = mix(zenC, linFogC, rain);
    vec3 horC = cSatur(moonC + sunC, 0.5);

    for(int i = 0; i < int(DirectionalLightToggleAndCountAndMaxDistance.y); i++){
        LightSourceWorldInfo ld = s_DirectionalLightSources[i];
        vec4 shP;
        int csIdx = -1;
        mat4 csProj[4] = { ld.shadowProj0, ld.shadowProj1, ld.shadowProj2, ld.shadowProj3 };
        for(int j = 0; j < 4; j++){
            shP = mul(csProj[j], vec4(wPos.xyz, 1.0));
            shP /= shP.w;
            if(length(clamp(shP.xyz, -1.0, 1.0) - shP.xyz) < 0.000001){ csIdx = j; break; }
        }
        if(csIdx != -1){
            float nDotsl = saturate(dot(vNor, normalize(mul(u_view, ld.shadowDirection).xyz)));
            float bias = ShadowBias[ld.shadowCascadeNumber] + ShadowSlopeBias[ld.shadowCascadeNumber] * clamp(tan(acos(nDotsl)), 0.0, 1.0);
            #if !BGFX_SHADER_LANGUAGE_GLSL
                shP.y *= -1.0;
            #endif
            shP.z -= bias / shP.w;
            shP.xy = shP.xy * 0.5 + 0.5;
            #if BGFX_SHADER_LANGUAGE_GLSL
                shP.z = shP.z * 0.5 + 0.5;
            #endif
            float fSmap = 0.0;
            float bofs = cm.a < 1.0 ? 0.002 : 0.0002;
            for(int y = -1 ; y <= 1 ; y++){
                for(int x = -1 ; x <= 1 ; x++){
                    vec2 ofs = vec2(float(x) * bofs, float(y) * bofs);
                    if(ld.shadowCascadeNumber == 0){
                        fSmap += shadow2DArray(s_ShadowCascades0, vec4((shP.xy + ofs) * CascadeShadowResolutions[csIdx], float(csIdx), shP.z)).r;
                    } else if(ld.shadowCascadeNumber == 1){
                        fSmap += shadow2DArray(s_ShadowCascades1, vec4((shP.xy + ofs) * CascadeShadowResolutions[csIdx], float(csIdx), shP.z)).r;
                    }
                }
            }
            lVis = fSmap * 0.111;
            float shadowFade = smoothstep(max(0.0, ShadowParams.y - 8.0), ShadowParams.y, -vPos.z);
            lVis = mix(lVis, 1.0, shadowFade);
            lVis = saturate(lVis);
        }
        if(cm.a > 0.0){
            lVis *= saturate(dot(vNor, normalize(mul(u_view, ld.worldSpaceDirection).xyz)));
        }
        lVis = mix(lVis, 0.0, rain);
    }
    vec3 ambL = vec3_splat(0.01) + (cSatur(zenC, 0.3) * 1.5 * ear.b);
        ambL += vec3(1.0, 0.7, 0.4) * ((ear.g * ear.g) * 0.25 + pow(ear.g, 16.0) * 6.0);
        ambL += (moonC + sunC) * lVis * 2.0;
    outCol = cSatur(outCol, 1.0 - (pow(saturate(MoonP.y), 0.7) * ear.b) * 0.5);
    outCol *= ambL;
    float nFogD = 1.0 - exp(-saturate(length(-wPos.xyz) * 0.01) * (0.05 - saturate(sunP.y) * 0.02));
    outCol = mix(outCol, zenC * (3.0 - saturate(sunP.y) * 0.5), nFogD);
    vec3 bFogC = mix(zenC, horC, exp(-saturate(nWP.y) * 4.0) * 0.2);
        bFogC += (sunC * getMie(sunP, nWP) * 4.0) + (moonC * getMie(MoonP, nWP));
    if(z >= 1.0){
        bFogC += (sunC + (moonC + vec3(0.1, 0.1, 0.0))) * smoothstep(0.9975, 1.0, dot(nWP, abs(sunP))) * 100.0 * saturate(nWP.y);
        vec2 cloudP = (nWP.xz / nWP.y) * 0.7;
            cloudP -= cloudP * Bayer64(gl_FragCoord.xy) * 0.05;
        float sDens = 1.8;
        vec3 cloDirC = horC * 1.5, cloAmbC = cSatur(zenC, 0.7);
        for(int i = 0; i < 10; i++, cloudP -= cloudP * 0.045){
            cloDirC = mix(cloDirC, cloAmbC, 0.2);
            bFogC = mix(bFogC, cloDirC, fbm(cloudP, sDens, ViewPositionAndTime.w) * smoothstep(0.0, 0.5, nWP.y));
            sDens += (i <= 6) ? -0.1 : 0.1;
        }
    }
    float bFogD = calculateFogIntensity(length(wPos.xyz), FogAndDistanceControl.z, FogAndDistanceControl.x, FogAndDistanceControl.y);
    outCol = mix(outCol, bFogC, bFogD);
    vec4 sunProj = mul(u_proj, vec4(mul(u_view, vec4(SunDir.y > 0.0 ? SunDir.xyz : MoonDir.xyz, 1.0)).xyz, 1.0));
        sunProj /= sunProj.w;
    #if !BGFX_SHADER_LANGUAGE_GLSL
        sunProj.y *= -1.0;
    #endif
	vec2 sunScr = (sunProj.xy / sunProj.z) * 0.5 + 0.5;
    vec2 rayDir = (sunScr - v_texcoord0) * 0.04;
	vec2 rayOri = v_texcoord0 + rayDir * Bayer64(gl_FragCoord.xy);
	float godRays = 0.0;
	for(int i = 0; i < 20; i++, rayOri += rayDir){
        godRays += step(1.0, texture2D(s_SceneDepth, rayOri).r) * cdist(rayOri) * 0.025;
    }
    outCol += sunC * godRays * getMie1(sunP, nWP);
    outCol += moonC * godRays * getMie1(MoonP, nWP);
    outCol *= vec3(2.0, 1.9, 1.8);
    outCol *= (1.0 - saturate(abs(sunP.y)) * 0.8);

    gl_FragColor = vec4(outCol * 3e4, 1.0);
#else
    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
#endif
}