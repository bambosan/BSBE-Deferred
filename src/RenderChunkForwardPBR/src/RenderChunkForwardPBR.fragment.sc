$input v_color0, v_fog, v_normal, v_tangent, v_bitangent, v_texcoord0, v_lightmapUV, v_worldPos, v_blockAmbientContribution, v_skyAmbientContribution
#if defined(FORWARD_PBR_TRANSPARENT)
    $input v_pbrTextureId
#endif

#include <bgfx_shader.sh>
#include <bgfx_compute.sh>

#ifdef FORWARD_PBR_TRANSPARENT

uniform vec4 SunDir;
uniform vec4 MoonDir;

uniform vec4 ShadowPCFWidth;
uniform vec4 ShadowParams;
uniform vec4 ShadowBias;
uniform vec4 ShadowSlopeBias;
uniform vec4 CascadeShadowResolutions;
uniform vec4 DirectionalLightToggleAndCountAndMaxDistance;

uniform vec4 FogColor;
uniform vec4 FogAndDistanceControl;

SAMPLER2D(s_MatTexture, 5);
SAMPLER2D(s_SeasonsTexture, 4);
SAMPLER2D(s_LightMapTexture, 6);

SAMPLER2DARRAYSHADOW(s_ShadowCascades0, 1);
SAMPLER2DARRAYSHADOW(s_ShadowCascades1, 9);

struct PBRTextureData {
    float colourToMaterialUvScale0;
    float colourToMaterialUvScale1;
    float colourToMaterialUvBias0;
    float colourToMaterialUvBias1;
    float colourToNormalUvScale0;
    float colourToNormalUvScale1;
    float colourToNormalUvBias0;
    float colourToNormalUvBias1;
    int flags;
    float uniformRoughness;
    float uniformEmissive;
    float uniformMetalness;
    float maxMipColour;
    float maxMipMer;
    float maxMipNormal;
    float pad;
};

BUFFER_RO(s_PBRData, PBRTextureData, 7);

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

vec2 getPBRDataUV(vec2 surfaceUV, vec2 uvScale, vec2 uvBias) {
    return (((surfaceUV) * (uvScale)) + uvBias);
}

float calculateFogIntensity(float cameraDepth, float maxDistance, float fogStart, float fogEnd){
    float dist = cameraDepth / maxDistance;
    return saturate((dist - fogStart) / (fogEnd - fogStart));
}

#endif

void main() {
#ifdef FORWARD_PBR_TRANSPARENT
    vec4 diffuse = texture2D(s_MatTexture, v_texcoord0);
    if(diffuse.a < 0.5){ discard; }
    diffuse *= v_color0;
    diffuse.rgb = pow(diffuse.rgb, vec3_splat(2.2));

    PBRTextureData pbrTextureData = s_PBRData[v_pbrTextureId];
    vec2 normalUVScale = vec2(pbrTextureData.colourToNormalUvScale0, pbrTextureData.colourToNormalUvScale1);
    vec2 normalUVBias = vec2(pbrTextureData.colourToNormalUvBias0, pbrTextureData.colourToNormalUvBias1);
    vec2 materialUVScale = vec2(pbrTextureData.colourToMaterialUvScale0, pbrTextureData.colourToMaterialUvScale1);
    vec2 materialUVBias = vec2(pbrTextureData.colourToMaterialUvBias0, pbrTextureData.colourToMaterialUvBias1);

    int kPBRTextureDataFlagHasMaterialTexture  = (1 << 0);
    // These two are mutually exclusive
    int kPBRTextureDataFlagHasNormalTexture    = (1 << 1);
    int kPBRTextureDataFlagHasHeightMapTexture = (1 << 2);

    float emissive = pbrTextureData.uniformEmissive;
    float metalness = pbrTextureData.uniformMetalness;
    float linearRoughness = pbrTextureData.uniformRoughness;
    float subSurfaceScatter = 1.0;
    if ((pbrTextureData.flags & kPBRTextureDataFlagHasMaterialTexture) == kPBRTextureDataFlagHasMaterialTexture) {
        vec2 uv = getPBRDataUV(v_texcoord0, materialUVScale, materialUVBias);
        vec4 texel = texture2D(s_MatTexture, uv);
        metalness = texel.r;
        emissive = texel.g;
        linearRoughness = texel.b;
        subSurfaceScatter = texel.a;
    }

    vec4 vPos = mul(u_view, vec4(v_worldPos, 1.0));
    vec4 clipP = mul(u_proj, vPos);
    vec3 ndc = clipP.xyz / clipP.w;
    vec2 uv = (ndc.xy + vec2(1.0, 1.0)) / 2.0;
    vec3 nWP = normalize(v_worldPos);
    vec3 wNor = normalize(v_normal);
    vec3 vNor = normalize(mul(u_view, vec4(wNor, 0.0)).xyz);

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
            shP = mul(csProj[j], vec4(v_worldPos, 1.0));
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
            float bofs = subSurfaceScatter < 1.0 ? 0.002 : 0.0002;
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
        if(subSurfaceScatter > 0.0){
            lVis *= saturate(dot(vNor, normalize(mul(u_view, ld.worldSpaceDirection).xyz)));
        }
        lVis = mix(lVis, 0.0, rain);
    }
    vec3 ambL = vec3_splat(0.01) + (cSatur(zenC, 0.3) * 1.5 * v_lightmapUV.y);
        ambL += vec3(1.0, 0.7, 0.4) * ((v_lightmapUV.x * v_lightmapUV.x) * 0.25 + pow(v_lightmapUV.x, 16.0) * 6.0);
        ambL += mix((moonC + sunC) * 2.0, linFogC, rain) * lVis;
    diffuse.rgb = cSatur(diffuse.rgb, 1.0 - (pow(saturate(MoonP.y), 0.7) * v_lightmapUV.y) * 0.5);
    diffuse.rgb *= ambL;
    float nFogD = 1.0 - exp(-saturate(length(-v_worldPos) * 0.01) * (0.05 - saturate(sunP.y) * 0.02));
    diffuse.rgb = mix(diffuse.rgb, zenC * (3.0 - saturate(sunP.y) * 0.5), nFogD);
    vec3 bFogC = mix(zenC, horC, exp(-saturate(nWP.y) * 4.0) * 0.2);
        bFogC += (sunC * getMie(sunP, nWP) * 4.0) + (moonC * getMie(MoonP, nWP));
    float bFogD = calculateFogIntensity(length(v_worldPos), FogAndDistanceControl.z, FogAndDistanceControl.x, FogAndDistanceControl.y);
    diffuse.rgb = mix(diffuse.rgb, bFogC, bFogD);
    diffuse.rgb *= vec3(2.0, 1.9, 1.8);
    diffuse.rgb *= (1.0 - saturate(abs(sunP.y)) * 0.8);
    diffuse.rgb *= 3e4;

    gl_FragColor = diffuse;
#else

    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
#endif
}
