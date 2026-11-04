#version 450
// One layer per animation frame. The layer index is supplied as a push constant.
// 每个 array layer 对应一帧；帧号通过 push constant 传入。
layout(set = 0, binding = 0) uniform sampler2DArray uFrames;

layout(push_constant) uniform Push {
  uint layer;
} pc;

// The shared vertex shader emits vUvAlpha (location 0) and vUvRgb (location 1).
// Sequence textures use the RGB UV; alpha is stored directly in the RGBA layer.
layout(location = 1) in vec2 vUvRgb;
layout(location = 0) out vec4 outColor;

void main() {
  outColor = texture(uFrames, vec3(vUvRgb, float(pc.layer)));
}
