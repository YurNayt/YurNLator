#version 450

// Separable Lanczos2 resampling (4x4 / 16 source texels).
// Intended as a high-quality spatial texture filter between bilinear and heavier upscalers.

layout(push_constant) uniform PC {
    float ndcX0;
    float ndcY0;
    float ndcX1;
    float ndcY1;
    int   useTexAlpha;
    float srcW;
    float srcH;
    float outW;
    float outH;
    int   effectId;
    float sharpness;
    layout(offset = 56) float brightness;
    layout(offset = 60) float contrast;
    layout(offset = 64) float gamma;
} pc;

// YurNLator screen effects (brightness / contrast / gamma); identity at 0 / 1 / 1.
vec3 yurnColor(vec3 c) {
    c = pow(max(c, vec3(0.0)), vec3(1.0 / max(pc.gamma, 0.01)));
    c = (c - 0.5) * pc.contrast + 0.5 + pc.brightness;
    return clamp(c, 0.0, 1.0);
}

layout(binding = 0) uniform sampler2D texSampler;
layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265358979323846;

float sinc(float x) {
    x = abs(x);
    if (x < 1e-5) return 1.0;
    float p = PI * x;
    return sin(p) / p;
}

float lanczos2(float x) {
    x = abs(x);
    if (x >= 2.0) return 0.0;
    return sinc(x) * sinc(x * 0.5);
}

vec4 loadSource(ivec2 p) {
    ivec2 size = textureSize(texSampler, 0);
    return texelFetch(texSampler, clamp(p, ivec2(0), size - ivec2(1)), 0);
}

void main() {
    vec2 srcSize = max(vec2(pc.srcW, pc.srcH), vec2(1.0));
    vec2 srcPos = fragTexCoord * srcSize - vec2(0.5);
    ivec2 base = ivec2(floor(srcPos));
    vec2 fracPart = srcPos - floor(srcPos);

    vec4 accum = vec4(0.0);
    float totalWeight = 0.0;

    for (int y = -1; y <= 2; ++y) {
        float wy = lanczos2(float(y) - fracPart.y);
        for (int x = -1; x <= 2; ++x) {
            float wx = lanczos2(float(x) - fracPart.x);
            float w = wx * wy;
            accum += loadSource(base + ivec2(x, y)) * w;
            totalWeight += w;
        }
    }

    vec4 color = accum / max(abs(totalWeight), 1e-6);
    if (pc.useTexAlpha == 0) color.a = 1.0;
    vec4 clamped = clamp(color, 0.0, 1.0);
    outColor = vec4(yurnColor(clamped.rgb), clamped.a);
}
