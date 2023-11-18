$input v_texcoord0, v_fog, v_occlusionHeight, v_occlusionUV, v_worldPos

#include <bgfx_shader.sh>
#include <MinecraftRenderer.Materials/FogUtil.dragonh>

uniform vec4 Dimensions;
uniform vec4 ViewPosition;
uniform vec4 UVOffsetAndScale;
uniform vec4 OcclusionHeightOffset;
uniform vec4 FogColor;
uniform vec4 FogAndDistanceControl;
uniform vec4 PositionForwardOffset;
uniform vec4 PositionBaseOffset;
uniform vec4 Velocity;

SAMPLER2D(s_WeatherTexture, 12);
SAMPLER2D(s_OcclusionTexture, 6);
SAMPLER2D(s_LightingTexture, 4);

float getOcclusionHeight(const vec4 occlusionTextureSample) {
    float height = occlusionTextureSample.g + (occlusionTextureSample.b * 255.0f) - (OcclusionHeightOffset.x / 255.0f);
    return height;
}

float getOcclusionLuminance(const vec4 occlusionTextureSample) {
    return occlusionTextureSample.r;
}

bool isOccluded(const vec2 occlusionUV, const float occlusionHeight, const float occlusionHeightThreshold) {
#ifndef FLIP_OCCLUSION
#define OCCLUSION_OPERATOR <
#else
#define OCCLUSION_OPERATOR >
#endif

#ifndef NO_OCCLUSION
	// clamp the uvs
	return ( occlusionUV.x >= 0.0 && occlusionUV.x <= 1.0 && 
			 occlusionUV.y >= 0.0 && occlusionUV.y <= 1.0 && 
			 occlusionHeight OCCLUSION_OPERATOR occlusionHeightThreshold);
#else
	return false;
#endif
}

#if FORWARD_PBR_TRANSPARENT
uniform vec4 SunDir;
uniform vec4 MoonDir;
uniform vec4 CameraLightIntensity;

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
	vec4 diffuse = texture2D(s_WeatherTexture, v_texcoord0);
    vec4 occlusionLuminanceAndHeightThreshold = texture2D(s_OcclusionTexture, v_occlusionUV);

    float occlusionLuminance = getOcclusionLuminance(occlusionLuminanceAndHeightThreshold);
    float occlusionHeightThreshold = getOcclusionHeight(occlusionLuminanceAndHeightThreshold);
	
    if (isOccluded(v_occlusionUV, v_occlusionHeight, occlusionHeightThreshold)) {
        diffuse.a = 0.0;
    } else {
        float mixAmount = (v_occlusionHeight - occlusionHeightThreshold) * 25.0;
        float uvX = occlusionLuminance - (mixAmount * occlusionLuminance);
        vec2 lightingUV = vec2(uvX, 1.0);

        diffuse.rgb = pow(diffuse.rgb, vec3_splat(2.2));
        
        #if FORWARD_PBR_TRANSPARENT
        vec3 nWP = normalize(v_worldPos);
        vec3 sunP = normalize(SunDir.xyz);
        float sunH = saturate(sunP.y);
        vec3 moonP = normalize(MoonDir.xyz);
        float moonH = saturate(moonP.y);

        vec3 linFogC = pow(FogColor.rgb, vec3_splat(2.2));
        // ambient - diffuse
        diffuse.rgb *= (vec3_splat(0.01) + vec3(1.0, 0.6, 0.3) * ((lightingUV.x * lightingUV.x) * 0.2 + pow(lightingUV.x, 16.0) * 5.0));
        // near fog
        diffuse.rgb = mix(diffuse.rgb, linFogC, 1.0 - exp(-saturate(length(-v_worldPos.xyz) * 0.01) * 0.1 * CameraLightIntensity.y));

        vec3 bFogC = mix(linFogC, linFogC, exp(-saturate(nWP.y) * 2.0) * 0.1) + (linFogC * getMie(sunP, nWP) * 4.0) + (linFogC * getMie(moonP, nWP));
        diffuse.rgb = mix(diffuse.rgb, bFogC, v_fog.a);
        diffuse.rgb *= vec3(2.0, 1.9, 1.8);
        diffuse.rgb *= (1.0 - (sunH + moonH) * 0.8);
        #endif
    }
    diffuse.rgb = max(vec3_splat(0.0), diffuse.rgb * 1000.0);
    gl_FragColor = diffuse;
}
