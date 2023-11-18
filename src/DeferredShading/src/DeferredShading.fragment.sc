$input v_projPosition, v_texcoord0, v_time

#include <bgfx_shader.sh>
#include <bgfx_compute.sh>

#if !defined(FALLBACK) && (BGFX_SHADER_LANGUAGE_GLSL >= 310 || BGFX_SHADER_LANGUAGE_HLSL >= 500 || BGFX_SHADER_LANGUAGE_PSSL || BGFX_SHADER_LANGUAGE_SPIRV || BGFX_SHADER_LANGUAGE_METAL)

uniform vec4 SunDir;
uniform vec4 MoonDir;
uniform vec4 ShadowParams;
uniform vec4 ShadowBias;
uniform vec4 ShadowSlopeBias;
uniform vec4 CascadeShadowResolutions;
uniform vec4 DirectionalLightToggleAndCountAndMaxDistance;
uniform vec4 PrepassUVOffset;
uniform vec4 FogColor;
uniform vec4 FogAndDistanceControl;
uniform vec4 CameraLightIntensity;

SAMPLER2D(s_ColorMetalness, 2);
SAMPLER2D(s_SceneDepth, 10);
SAMPLER2D(s_Normal, 7);
SAMPLER2D(s_EmissiveAmbientLinearRoughness, 4);
SAMPLER2DARRAYSHADOW(s_ShadowCascades0, 11);
SAMPLER2DARRAYSHADOW(s_ShadowCascades1, 12);

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

BUFFER_RO(s_DirectionalLightSources, LightSourceWorldInfo, 3);

float Bayer2(vec2 a){
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

float getMie(vec3 lPos, vec3 pos){
    return exp(-distance(pos, lPos) * 2.0) * exp(-saturate(pos.y) * 4.0);
}

float getMie1(vec3 lPos, vec3 pos){
    return exp(-distance(pos, lPos) * 2.0);
}

float hash(float h){
    return fract(sin(h) * 43758.5453);
}

float noise2d(vec2 pos){
    vec2 ip = floor(pos);
    vec2 fp = fract(pos);
        fp = fp * fp * (3.0 - 2.0 * fp);
    float n = ip.x + ip.y * 57.0;
    return mix(mix(hash(n), hash(n + 1.0), fp.x), mix(hash(n + 57.0), hash(n + 58.0), fp.x), fp.y);
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

float fbm(vec2 pos, float fTime){
    float sum = 0.0;
    float den = 1.0;
    pos *= 1.5;
    pos += fTime * 0.001;
    for(int i = 0; i < 4; i++){
        sum += noise2d(pos) * den;
        den *= 0.5;
        pos *= 2.5;
        pos += sum;
        pos -= fTime * 0.1;
    }
    return saturate(1.0 - sum) * 0.25;
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

vec4 projToView(vec4 p, mat4 inverseProj){
    p = vec4(p.x * inverseProj[0][0], p.y * inverseProj[1][1], p.w * inverseProj[2][3],
        p.z * inverseProj[3][2] + p.w * inverseProj[3][3]);
    p /= p.w;
    return p;
}

vec2 octWrap(vec2 v){
    return (1.0 - abs(v.yx)) * ((2.0 * step(0.0, v)) - 1.0);
}

vec3 octToNdirSnorm(vec2 p) {
    vec3 n = vec3(p.xy, 1.0 - abs(p.x) - abs(p.y));
    n.xy = (n.z < 0.0) ? octWrap(n.xy) : n.xy;
    return normalize(n);
}

float cdist(vec2 coord){
    return saturate(1.0 - max(abs(coord.x - 0.5), abs(coord.y - 0.5)) * 2.0);
}

float calculateFogIntensity(float cameraDepth, float maxDistance, float fogStart, float fogEnd){
    float dist = cameraDepth / maxDistance;
    return saturate((dist - fogStart) / (fogEnd - fogStart));
}
#endif

void main(){
#if !defined(FALLBACK) && (BGFX_SHADER_LANGUAGE_GLSL >= 310 || BGFX_SHADER_LANGUAGE_HLSL >= 500 || BGFX_SHADER_LANGUAGE_PSSL || BGFX_SHADER_LANGUAGE_SPIRV || BGFX_SHADER_LANGUAGE_METAL)
    vec2 uv = vec2(v_texcoord0.x * PrepassUVOffset.x + PrepassUVOffset.y, v_texcoord0.y);
    float sDepth = texture2D(s_SceneDepth, uv).r;
    vec4 vPos = projToView(vec4(v_projPosition.xy, sDepth, 1.0), u_invProj);
    vec4 wPos = mul(u_invView, vec4(vPos.xyz, 1.0));
    vec3 nWP = normalize(wPos.xyz);
    vec3 wNor = octToNdirSnorm(texture2D(s_Normal, uv).xy);
    vec4 cm = texture2D(s_ColorMetalness, uv);
    vec4 ear = texture2D(s_EmissiveAmbientLinearRoughness, uv);
    vec3 outCol = pow(cm.rgb, vec3_splat(2.2));

    float lVis = 1.0;
    float rain = 1.0 - smoothstep(0.3, 0.7, FogAndDistanceControl.x);

    if(int(DirectionalLightToggleAndCountAndMaxDistance.y) != 0){
        LightSourceWorldInfo ld = s_DirectionalLightSources[0];
        vec4 shP;
        int csIdx = -1;
        mat4 csProj[4] = { ld.shadowProj0, ld.shadowProj1, ld.shadowProj2, ld.shadowProj3 };
        for(int i = 0; i < 4; i++){
            shP = mul(csProj[i], vec4(wPos.xyz, 1.0));
            shP /= shP.w;
            if(length(clamp(shP.xyz, -1.0, 1.0) - shP.xyz) == 0.0){
                csIdx = i;
                break;
            }
        }

        bool sss = cm.a < 1.0;
        if(csIdx != -1){
            float bias = 0.0001 + 0.00042 * saturate(tan(acos(max(dot(wNor, normalize(ld.shadowDirection.xyz)), 0.0))));
            shP.z -= bias / shP.w;
            shP.y *= -1.0;
            shP.xy = shP.xy * 0.5 + 0.5;

            vec2 pDO[16] = {
                vec2(1.0, 1.0), vec2(-1.0, 1.0), vec2(1.0, -1.0), vec2(-1.0, -1.0),
                vec2(2.0, 0.0), vec2(0.0, 2.0), vec2(-2.0, 0.0), vec2(0.0, -2.0),
                vec2(3.0, 1.0), vec2(1.0, 3.0), vec2(-1.0, 3.0), vec2(3.0, -1.0),
                vec2(-3.0, 1.0), vec2(-1.0, -3.0), vec2(1.0, -3.0), vec2(-3.0, -1.0)
            };

            float fSmap = 0.0;
            for(int i = 0; i < (sss ? 16 : 4); i++){
                vec2 osp = (shP.xy + pDO[i] * (sss ? 0.001 : 0.0001)) * CascadeShadowResolutions[csIdx];
                if(ld.shadowCascadeNumber == 0){
                    fSmap += shadow2DArray(s_ShadowCascades0, vec4(osp, float(csIdx), shP.z)).r;
                } else if(ld.shadowCascadeNumber == 1){
                    fSmap += shadow2DArray(s_ShadowCascades1, vec4(osp, float(csIdx), shP.z)).r;
                }
            }
            float ndl = saturate(dot(wNor, normalize(ld.worldSpaceDirection.xyz)));
            lVis = saturate(fSmap * (sss ? 0.0625 : 0.24 * ndl));
            lVis = mix(lVis, ndl, smoothstep(max(0.0, ShadowParams.y - 8.0), ShadowParams.y, -vPos.z));
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
        vec3 zenC = mix(vec3(0.0, sunH * 0.05 + 0.001, sunH * 0.5 + 0.004), linFogC, rain);

        // ambient - diffuse
        outCol *= (vec3_splat(0.01) + (cSatur(zenC, 0.3) * (2.0 - rain * 1.5) * ear.b) + vec3(1.0, 0.6, 0.3) * ((ear.g * ear.g) * 0.2 + pow(ear.g, 16.0) * 5.0) + (moonC + sunC) * lVis * 2.0);
        // near fog
        outCol = mix(outCol, zenC * (3.0 - sunH * 0.5), 1.0 - exp(-saturate(length(-wPos.xyz) * 0.01) * max(0.05 - sunH * 0.02, rain * 0.1) * CameraLightIntensity.y));

        vec3 bFogC = mix(zenC, horC, exp(-saturate(nWP.y) * 2.0) * 0.1) + (sunC * getMie(sunP, nWP) * 4.0) + (moonC * getMie(moonP, nWP));

        if(sDepth >= 1.0){
            bFogC += normalize(sunC) * smoothstep(0.997, 0.999, dot(nWP, sunP)) * 5.0 * saturate(nWP.y);
            bFogC += normalize(moonC) * smoothstep(0.996, 0.998, dot(nWP, moonP)) * 5.0 * saturate(nWP.y);
        #if 1
            float tBSAng = mod((sunP.x - sunP.y * 0.5) * 3600.0, 3600.0) * 0.1;
            vec2 cloudP = (nWP.xz / nWP.y) * 0.7;
                cloudP -= cloudP * Bayer64(gl_FragCoord.xy) * 0.05;
            float sDens = 1.8;
            vec3 cloDirC = horC * (1.0 + (getMie1(sunP, nWP) + getMie1(moonP, nWP)));
            vec3 cloAmbC = cSatur(zenC, 0.7);
            for(int i = 0; i < 10; i++, cloudP -= cloudP * 0.045){
                cloDirC = mix(cloDirC, cloAmbC, 0.2);
                bFogC = mix(bFogC, cloDirC, fbm(cloudP, sDens, tBSAng) * smoothstep(0.0, 0.5, nWP.y));
                sDens += (i <= 6) ? -0.1 : 0.1;
            }
        #endif
        }

        float bFogD = calculateFogIntensity(length(wPos.xyz), FogAndDistanceControl.z, FogAndDistanceControl.x, FogAndDistanceControl.y);
        outCol = mix(outCol, bFogC, bFogD);
    #if 1
        vec4 sunProj = mul(u_proj, vec4(mul(u_view, vec4(ld.worldSpaceDirection.xyz, 1.0)).xyz, 1.0));
            sunProj /= sunProj.w;
            sunProj.y *= -1.0;
        vec2 sunScr = (sunProj.xy / sunProj.z) * 0.5 + 0.5;
        vec2 rayDir = (sunScr - v_texcoord0) * 0.05;
        vec2 rayOri = v_texcoord0 + rayDir * Bayer64(gl_FragCoord.xy);
        float godRays = 0.0;
        for(int i = 0; i < 16; i++, rayOri += rayDir) godRays += step(1.0, texture2D(s_SceneDepth, rayOri).r) * cdist(rayOri) * 0.025;
        outCol += sunC * godRays * getMie1(sunP, nWP) + moonC * 2.0 * godRays * getMie1(moonP, nWP);
    #endif
        outCol *= vec3(2.0, 1.9, 1.8);
        outCol *= (1.0 - (sunH + moonH) * 0.8);
    } else {
        outCol *= (vec3_splat(0.03) + vec3(1.0, 0.6, 0.3) * ((ear.g * ear.g) * 0.2 + pow(ear.g, 16.0) * 5.0));
    }

    gl_FragColor = vec4(max(vec3_splat(0.0), outCol * 1000.0), 1.0);
#else
    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
#endif
}