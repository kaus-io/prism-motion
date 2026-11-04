/* OpenH264 解码器的 C 封装：JVM 侧经 JNA、Kotlin/Native 侧经 cinterop 调用。 */
#ifndef PRISM_DECODE_H
#define PRISM_DECODE_H

#include <stdint.h>

typedef struct PrismDecoder PrismDecoder;

/* 创建解码器；失败返回 NULL。 / Creates a decoder; NULL on failure. */
PrismDecoder *prism_dec_create(void);

/* 销毁解码器。 / Destroys the decoder. */
void prism_dec_destroy(PrismDecoder *decoder);

/* 喂入 Annex-B 码流，解出一帧紧凑 I420。
 * 返回 1 表示出帧，0 表示需要更多数据，<0 表示失败。
 * / Feeds Annex-B data; 1 = frame produced, 0 = need more data, <0 = error. */
int prism_dec_decode(PrismDecoder *decoder, const uint8_t *data, int size, uint8_t *i420_out, int out_capacity);

/* 最近一帧的宽高。 / Width/height of the last decoded frame. */
int prism_dec_width(PrismDecoder *decoder);
int prism_dec_height(PrismDecoder *decoder);

#endif
