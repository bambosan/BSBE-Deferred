$input v_projPosition, v_texcoord0
#include <bgfx_shader.sh>
#include <bgfx_compute.sh>

#if !defined(FALLBACK) && (BGFX_SHADER_LANGUAGE_HLSL >= 500 || BGFX_SHADER_LANGUAGE_PSSL || BGFX_SHADER_LANGUAGE_SPIRV || BGFX_SHADER_LANGUAGE_METAL)
uniform vec4 PrepassUVOffset;
uniform vec4 SunDir;
uniform vec4 MoonDir;
uniform vec4 ShadowParams;
uniform vec4 CascadeShadowResolutions;
uniform vec4 DirectionalLightToggleAndCountAndMaxDistance;
uniform vec4 FogColor;
uniform vec4 FogAndDistanceControl;
uniform vec4 CameraLightIntensity;

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

struct BSBELight {
    vec3 linFogC;
    vec3 sunC;
    vec3 moonC;
    vec3 zenC;
    vec3 horC;
    float lmCY;
    float lmCX;
    float lVis;
    float sunVis;
    float moonVis;
    float rain;
    float fTime;
};

struct BSBEVec {
    vec3 vPos;
    vec3 sunPNor;
    vec3 moonPNor;
    vec3 shLPNor;
    vec3 wPos;
    vec3 wPosNor;
    vec3 wNor;
};

SAMPLER2D(s_ColorMetalness, 1);
SAMPLER2D(s_SceneDepth, 0);
SAMPLER2D(s_Normal, 2);
SAMPLER2D(s_EmissiveAmbientLinearRoughness, 3);
SAMPLER2DARRAYSHADOW(s_ShadowCascades0, 7);
SAMPLER2DARRAYSHADOW(s_ShadowCascades1, 9);
BUFFER_RO(s_DirectionalLightSources, LightSourceWorldInfo, 8);

float interleavedGradientNoise(vec2 uv){
    return fract(52.9829189 * fract(uv.x * 0.06711056 + uv.y * 0.00583715));
}

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

float calcBFog(float cameraDepth, float maxDistance, float fogStart, float fogEnd){
    float dist = cameraDepth / maxDistance;
    return saturate((dist - fogStart) / (fogEnd - fogStart));
}

vec4 projToView(vec4 p, mat4 inverseProj){
    p = vec4(p.x * inverseProj[0][0], p.y * inverseProj[1][1], p.w * inverseProj[2][3],
        p.z * inverseProj[3][2] + p.w * inverseProj[3][3]);
    p /= p.w;
    return p;
}

int getCsIndex(LightSourceWorldInfo ld, out vec4 shPos, vec3 wPos){
    mat4 csProj[4] = { ld.shadowProj0, ld.shadowProj1, ld.shadowProj2, ld.shadowProj3 };
    for(int i = 0; i < 4; i++){
        shPos = mul(csProj[i], vec4(wPos, 1.0));
        shPos /= shPos.w;
        if(length(clamp(shPos.xyz, -1.0, 1.0) - shPos.xyz) == 0.0) return i;
    }
    return -1;
}

float filteredSh(vec3 shPos, int csNum, int csIdx, int bstep, float bofs){
    vec2 pDO[16] = {
        vec2(1.0, 1.0), vec2(-1.0, 1.0), vec2(1.0, -1.0), vec2(-1.0, -1.0),
        vec2(2.0, 0.0), vec2(0.0, 2.0), vec2(-2.0, 0.0), vec2(0.0, -2.0),
        vec2(3.0, 1.0), vec2(1.0, 3.0), vec2(-1.0, 3.0), vec2(3.0, -1.0),
        vec2(-3.0, 1.0), vec2(-1.0, -3.0), vec2(1.0, -3.0), vec2(-3.0, -1.0)
    };
    float fSmap = 0.0;
    for(int i = 0; i < bstep; i++){
        vec2 osp = (shPos.xy + pDO[i] * bofs) * CascadeShadowResolutions[csIdx];
        if(csNum == 0){
            fSmap += shadow2DArray(s_ShadowCascades0, vec4(osp, float(csIdx), shPos.z)).r;
        } else {
            fSmap += shadow2DArray(s_ShadowCascades1, vec4(osp, float(csIdx), shPos.z)).r;
        }
    }
    return fSmap;
}

float shLight(LightSourceWorldInfo ld, BSBEVec bvd, BSBELight bld, bool sss){
    vec4 shPos;
    int csIdx = getCsIndex(ld, shPos, bvd.wPos);
    float fSmap = 1.0;
    if(csIdx != -1){
        float bias = 0.0001 + 0.00042 * saturate(tan(acos(max(dot(bvd.wNor, normalize(ld.shadowDirection.xyz)), 0.0))));
        shPos.xy = vec2(shPos.x, -shPos.y) * 0.5 + 0.5;
        shPos.z -= bias / shPos.w;
        fSmap = filteredSh(shPos.xyz, ld.shadowCascadeNumber, csIdx, (sss ? 16 : 4), (sss ? 0.001 : 0.0001));
        fSmap = saturate(fSmap * (sss ? 0.0625 : 0.24 * saturate(dot(bvd.wNor, normalize(ld.worldSpaceDirection.xyz)))));    
    }
    fSmap = mix(fSmap, saturate(dot(bvd.wNor, bvd.shLPNor)), smoothstep(max(0.0, ShadowParams.y - 8.0), ShadowParams.y, -bvd.vPos.z));
    fSmap = saturate(fSmap - bld.rain);
    return fSmap;
}

vec3 ambDiff(BSBELight bld){
    return (vec3_splat(0.01) + (cSatur(bld.zenC, 0.3) * (2.0 - bld.rain * 1.5) * bld.lmCY) + vec3(1.0, 0.6, 0.3) * ((bld.lmCX * bld.lmCX) * 0.2 + pow(bld.lmCX, 16.0) * 5.0) + (bld.moonC + bld.sunC) * bld.lVis * 2.0);
}

vec3 nearFog(vec3 backg, BSBELight bld, BSBEVec bvd){
    return mix(backg, bld.zenC * (3.0 - bld.sunVis * 0.5), 1.0 - exp(-saturate(length(-bvd.wPos) * 0.01) * max(0.05 - bld.sunVis * 0.02, bld.rain * 0.1) * CameraLightIntensity.y));
}

vec3 borderFog(BSBELight bld, BSBEVec bvd){
    return mix(bld.zenC, bld.horC, exp(-saturate(bvd.wPosNor.y) * 2.0) * 0.1) + (bld.sunC * getMie(bvd.sunPNor, bvd.wPosNor) * 4.0) + (bld.moonC * getMie(bvd.moonPNor, bvd.wPosNor));
}

void smVis(inout vec3 backg, BSBELight bld, BSBEVec bvd){
    backg += normalize(bld.sunC) * smoothstep(0.997, 0.999, dot(bvd.wPosNor, bvd.sunPNor)) * 5.0 * saturate(bvd.wPosNor.y);
    backg += normalize(bld.moonC) * smoothstep(0.996, 0.998, dot(bvd.wPosNor, bvd.moonPNor)) * 5.0 * saturate(bvd.wPosNor.y);
}

void cloudMap(inout vec3 backg, BSBELight bld, BSBEVec bvd, float dithering){
    vec2 cloudP = (bvd.wPosNor.xz / bvd.wPosNor.y) * 0.7;
        cloudP -= cloudP * dithering;
    float sDens = 1.8;
    vec3 cloDirC = bld.horC * (1.0 + (getMie1(bvd.sunPNor, bvd.wPosNor) + getMie1(bvd.moonPNor, bvd.wPosNor)));
    vec3 cloAmbC = cSatur(bld.zenC, 0.7);
    for(int i = 0; i < 10; i++){
        float fmap = fbm(cloudP, sDens, bld.fTime);
        cloDirC = mix(cloDirC, cloAmbC, 0.2);
        backg = mix(backg, cloDirC, fmap * smoothstep(0.0, 0.5, bvd.wPosNor.y));
        sDens += (i <= 6) ? -0.1 : 0.1;
        cloudP -= cloudP * 0.045;
    }
}

void ssGR(inout vec3 backg, BSBELight bld, BSBEVec bvd, vec2 uv, float dithering){
    vec4 lProj = mul(u_proj, vec4(mul(u_view, vec4(bvd.shLPNor, 1.0)).xyz, 1.0));
        lProj /= lProj.w;
        lProj.y *= -1.0;
    vec2 sunScr = (lProj.xy / lProj.z) * 0.5 + 0.5;
    vec2 rayDir = (sunScr - uv) * 0.05;
    vec2 rayOri = uv + rayDir * dithering;
    float godRays = 0.0;
    for(int i = 0; i < 16; i++){
        godRays += step(1.0, texture2D(s_SceneDepth, rayOri).r) * cdist(rayOri) * 0.025;
        rayOri += rayDir;
    }
    backg += bld.sunC * godRays * getMie1(bvd.sunPNor, bvd.wPosNor);
    backg += bld.moonC * 2.0 * godRays * getMie1(bvd.moonPNor, bvd.wPosNor);
}
#endif

void main(){
#if !defined(FALLBACK) && (BGFX_SHADER_LANGUAGE_HLSL >= 500 || BGFX_SHADER_LANGUAGE_PSSL || BGFX_SHADER_LANGUAGE_SPIRV || BGFX_SHADER_LANGUAGE_METAL)
    vec2 uv = vec2(v_texcoord0.x * PrepassUVOffset.x + PrepassUVOffset.y, v_texcoord0.y);
    float sDepth = texture2D(s_SceneDepth, uv).r;
    vec4 cm = texture2D(s_ColorMetalness, uv);
    vec4 ear = texture2D(s_EmissiveAmbientLinearRoughness, uv);
    vec3 outCol = pow(cm.rgb, vec3_splat(2.2));

    BSBEVec bvd;
        bvd.vPos = projToView(vec4(v_projPosition.xy, sDepth, 1.0), u_invProj).xyz;
        bvd.wPos = mul(u_invView, vec4(bvd.vPos, 1.0)).xyz;
        bvd.wPosNor = normalize(bvd.wPos);
        bvd.wNor = octToNdirSnorm(texture2D(s_Normal, uv).xy);
        bvd.sunPNor = normalize(SunDir.xyz);
        bvd.moonPNor = normalize(MoonDir.xyz);
        bvd.shLPNor = bvd.sunPNor.y > 0.0 ? bvd.sunPNor : bvd.moonPNor;

    BSBELight bld;
        bld.sunVis = saturate(bvd.sunPNor.y);
        bld.moonVis = saturate(bvd.moonPNor.y);
        bld.rain = saturate(1.0 - smoothstep(0.3, 0.7, FogAndDistanceControl.x));
        bld.linFogC = pow(FogColor.rgb, vec3_splat(2.2));
        bld.sunC = mix(vec3((1.0 - bld.sunVis) * 0.5 + bld.sunVis, bld.sunVis, bld.sunVis * bld.sunVis) * bld.sunVis, bld.linFogC, bld.rain);
        bld.moonC = mix(vec3(bld.moonVis * 0.03, bld.moonVis * 0.06, bld.moonVis * 0.1) * bld.moonVis, bld.linFogC, bld.rain);
        bld.horC = cSatur(bld.moonC + bld.sunC, 0.5);
        bld.zenC = mix(vec3(0.0, bld.sunVis * 0.05 + 0.001, bld.sunVis * 0.5 + 0.004), bld.linFogC, bld.rain);
        bld.lmCX = ear.g;
        bld.lmCY = ear.b;
        bld.lVis = 1.0;
        bld.fTime = mod((bvd.sunPNor.x - bvd.sunPNor.y * 0.5) * 3600.0, 3600.0) * 0.1;

    if(int(DirectionalLightToggleAndCountAndMaxDistance.y) != 0){
        LightSourceWorldInfo ld = s_DirectionalLightSources[0];
        bld.lVis = shLight(ld, bvd, bld, ear.r < 1.0);
        outCol *= ambDiff(bld);
        outCol = nearFog(outCol, bld, bvd);
        vec3 bFogC = borderFog(bld, bvd);
        float dithering = interleavedGradientNoise(gl_FragCoord.xy);
        if(sDepth >= 1.0){
            smVis(bFogC, bld, bvd);
            cloudMap(bFogC, bld, bvd, dithering * 0.05);
        }
        float bFogD = calcBFog(length(bvd.wPos), FogAndDistanceControl.z, FogAndDistanceControl.x, FogAndDistanceControl.y);
        outCol = mix(outCol, bFogC, bFogD);
        ssGR(outCol, bld, bvd, uv, dithering);
        outCol *= vec3(2.0, 1.9, 1.8) * (1.0 - (bld.sunVis + bld.moonVis) * 0.8 * CameraLightIntensity.y);
    } else {
        outCol *= (vec3_splat(0.03) + vec3(1.0, 0.6, 0.3) * ((bld.lmCX * bld.lmCX) * 0.2 + pow(bld.lmCX, 16.0) * 5.0));
        float bFogD = calcBFog(length(bvd.wPos), FogAndDistanceControl.z, FogAndDistanceControl.x, FogAndDistanceControl.y);
        outCol = mix(outCol, bld.linFogC, bFogD);
    }

    gl_FragColor = vec4(max(vec3_splat(0.0), outCol * 1000.0), 1.0);
#else
    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
#endif
}