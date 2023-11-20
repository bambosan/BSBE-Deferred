$input v_texcoord0
#include <bgfx_shader.sh>

SAMPLER2D(s_HDRi, 0);
SAMPLER2D(s_BlurPyramidTexture, 1);
SAMPLER2D(s_DepthTexture, 3);

float luminance(vec3 clr) {
    return dot(clr, vec3(0.2126, 0.7152, 0.0722));
}

vec4 HighPass(vec4 col){
    float lum = luminance(col.rgb);
    return vec4(col.rgb, lum);
}

vec4 HighPassDFDownsample(sampler2D srcImg, sampler2D depthImg, vec2 uv, vec2 pixelOffsets){
    vec4 col = 0.5 * HighPass(texture2D(srcImg, uv));
        col += 0.125 * HighPass(texture2D(srcImg, uv + vec2(pixelOffsets.x, pixelOffsets.y)));
        col += 0.125 * HighPass(texture2D(srcImg, uv + vec2(-pixelOffsets.x, pixelOffsets.y)));
        col += 0.125 * HighPass(texture2D(srcImg, uv + vec2(pixelOffsets.x, -pixelOffsets.y)));
        col += 0.125 * HighPass(texture2D(srcImg, uv + vec2(-pixelOffsets.x, -pixelOffsets.y)));
    float minRange = 0.95;
    float maxRange = 0.98;
    float d = texture2D(depthImg, uv).r;
        d = ((d * maxRange) - minRange) / (maxRange - minRange);
        d = clamp(d, 0.5, 1.0);
        col *= (d * d);
    return col;
}

vec4 DualFilterDownsample(sampler2D srcImg, vec2 uv, vec2 pixelOffsets) {
    vec4 col = 0.5 * texture2D(srcImg, uv);
        col += 0.125 * texture2D(srcImg, uv + vec2(pixelOffsets.x, pixelOffsets.y));
        col += 0.125 * texture2D(srcImg, uv + vec2(-pixelOffsets.x, pixelOffsets.y));
        col += 0.125 * texture2D(srcImg, uv + vec2(pixelOffsets.x, -pixelOffsets.y));
        col += 0.125 * texture2D(srcImg, uv + vec2(-pixelOffsets.x, -pixelOffsets.y));
    return col;
}

vec4 DualFilterDownsampleWithDepthErosion(sampler2D srcImg, vec2 uv, vec2 pixelOffsets) {
    vec4 a = texture2D(srcImg, uv);
    vec4 b = texture2D(srcImg, uv + vec2(pixelOffsets.x, pixelOffsets.y));
    vec4 c = texture2D(srcImg, uv + vec2(-pixelOffsets.x, pixelOffsets.y));
    vec4 d = texture2D(srcImg, uv + vec2(pixelOffsets.x, -pixelOffsets.y));
    vec4 e = texture2D(srcImg, uv + vec2(-pixelOffsets.x, -pixelOffsets.y));
    vec4 col = vec4(0.5 * a.rgb, max(a.a, max(b.a, max(c.a, max(d.a, e.a)))));
        col.rgb += 0.125 * b.rgb;
        col.rgb += 0.125 * c.rgb;
        col.rgb += 0.125 * d.rgb;
        col.rgb += 0.125 * e.rgb;
    return col;
}

vec4 DualFilterUpsample(sampler2D srcImg, vec2 uv, vec2 pixelOffsets) {
    vec4 col = 0.166 * texture2D(srcImg, uv + vec2(0.5 * pixelOffsets.x, 0.5 * pixelOffsets.y));
        col += 0.166 * texture2D(srcImg, uv + vec2(-0.5 * pixelOffsets.x, 0.5 * pixelOffsets.y));
        col += 0.166 * texture2D(srcImg, uv + vec2(0.5 * pixelOffsets.x, -0.5 * pixelOffsets.y));
        col += 0.166 * texture2D(srcImg, uv + vec2(-0.5 * pixelOffsets.x, -0.5 * pixelOffsets.y));
        col += 0.083 * texture2D(srcImg, uv + vec2(pixelOffsets.x, pixelOffsets.y));
        col += 0.083 * texture2D(srcImg, uv + vec2(-pixelOffsets.x, pixelOffsets.y));
        col += 0.083 * texture2D(srcImg, uv + vec2(pixelOffsets.x, -pixelOffsets.y));
        col += 0.083 * texture2D(srcImg, uv + vec2(-pixelOffsets.x, -pixelOffsets.y));
    return col;
}

vec4 BloomHighPass(vec2 texcoord0) {
    float xOffset = 1.5 * abs(dFdx(texcoord0.x));
    float yOffset = 1.5 * abs(dFdy(texcoord0.y));
    return HighPassDFDownsample(s_HDRi, s_DepthTexture, texcoord0, vec2(xOffset, yOffset));
}

vec4 DFDownSample(vec2 texcoord0) {
    float xOffset = 1.5 * abs(dFdx(texcoord0.x));
    float yOffset = 1.5 * abs(dFdy(texcoord0.y));
    return DualFilterDownsample(s_BlurPyramidTexture, texcoord0, vec2(xOffset, yOffset));
}

vec4 DFDownSampleWithDepthErosion(vec2 texcoord0) {
    float xOffset = 1.5 * abs(dFdx(texcoord0.x));
    float yOffset = 1.5 * abs(dFdy(texcoord0.y));
    return DualFilterDownsampleWithDepthErosion(s_BlurPyramidTexture, texcoord0, vec2(xOffset, yOffset));
}

vec4 DFUpSample(vec2 texcoord0) {
    float xOffset = 4.0 * abs(dFdx(texcoord0.x));
    float yOffset = 4.0 * abs(dFdy(texcoord0.y));
    return DualFilterUpsample(s_BlurPyramidTexture, texcoord0, vec2(xOffset, yOffset));
}

vec4 BloomBlend(vec2 texcoord0) {
    float xOffset = 4.0 * abs(dFdx(texcoord0.x));
    float yOffset = 4.0 * abs(dFdy(texcoord0.y));
    vec4 bloom = DualFilterUpsample(s_BlurPyramidTexture, texcoord0, vec2(xOffset, yOffset));
    vec3 result = texture2D(s_HDRi, texcoord0).rgb + (0.15 * bloom.rgb);
    return vec4(result, 1.0);
}

void main(){
#if BLOOM_HIGH_PASS
    gl_FragColor = BloomHighPass(v_texcoord0);
#elif DFDOWN_SAMPLE
    gl_FragColor = DFDownSample(v_texcoord0);
#elif DFDOWN_SAMPLE_WITH_DEPTH_EROSION
    gl_FragColor = DFDownSampleWithDepthErosion(v_texcoord0);
#elif DFUP_SAMPLE
    gl_FragColor = DFUpSample(v_texcoord0);
#elif BLOOM_BLEND
    gl_FragColor = BloomBlend(v_texcoord0);
#endif
}
