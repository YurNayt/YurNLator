#version 450
layout(binding = 0) uniform sampler2D texSampler;
layout(push_constant) uniform PC {
    float ndcX0;
    float ndcY0;
    float ndcX1;
    float ndcY1;
    int   useTexAlpha;
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
layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;
void main() {
    vec4 c = texture(texSampler, fragTexCoord);

    outColor = vec4(yurnColor(c.rgb), pc.useTexAlpha != 0 ? c.a : 1.0);
}
