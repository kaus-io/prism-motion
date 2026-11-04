/* OpenH264 的 C 封装：Kotlin/Native 直接调 C 接口，避免 C++ 虚表互操作。
 * 输出为 Annex-B 字节流，由 Kotlin 侧拆 NAL 并封装成 MP4。 */
#ifndef PRISM_CODEC_H
#define PRISM_CODEC_H

#include <stdint.h>

typedef struct PrismEncoder PrismEncoder;

/* 创建编码器；失败返回 NULL。 */
PrismEncoder *prism_enc_create(int width, int height, int fps, int bitrate_kbps);

/* 编码一帧 RGBA；返回写入 out 的字节数（0 表示该帧被跳过，-1 失败）。
 * keyframe 置 1 表示这是 IDR。 */
int prism_enc_encode(PrismEncoder *enc, const uint8_t *rgba, uint8_t *out, int out_capacity, int *keyframe);

void prism_enc_destroy(PrismEncoder *enc);

#endif
