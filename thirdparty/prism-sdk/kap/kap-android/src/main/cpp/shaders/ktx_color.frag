#version 450
layout(set = 0, binding = 0) uniform sampler2DArray uFrames;
layout(location = 1) in vec2 vUvRgb;
layout(location = 0) out vec4 outColor;
layout(push_constant) uniform Push {
    uint layer;
    layout(offset = 16) vec4 row0;
    vec4 row1;
    vec4 row2;
} pc;
void main() {
    vec4 pixel = texture(uFrames, vec3(vUvRgb, float(pc.layer)));
    vec3 rgb = vec3(dot(pc.row0.xyz, pixel.rgb), dot(pc.row1.xyz, pixel.rgb),
                    dot(pc.row2.xyz, pixel.rgb));
    // RenderEffect color matrices operate on premultiplied surface colors.
    // Preserve alpha and bound transformed premultiplied RGB to [0, alpha].
    outColor = vec4(clamp(rgb, vec3(0.0), vec3(pixel.a)), pixel.a);
}
