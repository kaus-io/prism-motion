#include "prism_decode.h"

#include <stdlib.h>
#include <string.h>

#include "codec_api.h"

struct PrismDecoder {
    ISVCDecoder *svc;
    int width;
    int height;
};

PrismDecoder *prism_dec_create(void) {
    PrismDecoder *decoder = (PrismDecoder *)calloc(1, sizeof(PrismDecoder));
    if (!decoder) return NULL;

    if (WelsCreateDecoder(&decoder->svc) != 0 || !decoder->svc) {
        free(decoder);
        return NULL;
    }

    SDecodingParam param;
    memset(&param, 0, sizeof(param));
    param.sVideoProperty.eVideoBsType = VIDEO_BITSTREAM_DEFAULT;
    if ((*decoder->svc)->Initialize(decoder->svc, &param) != 0) {
        WelsDestroyDecoder(decoder->svc);
        free(decoder);
        return NULL;
    }
    return decoder;
}

void prism_dec_destroy(PrismDecoder *decoder) {
    if (!decoder) return;
    if (decoder->svc) {
        (*decoder->svc)->Uninitialize(decoder->svc);
        WelsDestroyDecoder(decoder->svc);
    }
    free(decoder);
}

int prism_dec_decode(PrismDecoder *decoder, const uint8_t *data, int size, uint8_t *i420_out, int out_capacity) {
    if (!decoder || !data || size <= 0 || !i420_out) return -1;

    unsigned char *planes[3] = {NULL, NULL, NULL};
    SBufferInfo info;
    memset(&info, 0, sizeof(info));

    int ret = (*decoder->svc)->DecodeFrameNoDelay(decoder->svc, data, size, planes, &info);
    if (ret != 0) return ret < 0 ? -1 : 0;
    if (info.iBufferStatus != 1) return 0;

    int width = info.UsrData.sSystemBuffer.iWidth;
    int height = info.UsrData.sSystemBuffer.iHeight;
    if (width <= 0 || height <= 0) return 0;

    int y_size = width * height;
    int uv_size = (width / 2) * (height / 2);
    int needed = y_size + 2 * uv_size;
    if (needed > out_capacity) return -2;

    int stride_y = info.UsrData.sSystemBuffer.iStride[0];
    int stride_uv = info.UsrData.sSystemBuffer.iStride[1];

    for (int row = 0; row < height; row++) {
        memcpy(i420_out + row * width, planes[0] + row * stride_y, (size_t)width);
    }
    uint8_t *u_out = i420_out + y_size;
    uint8_t *v_out = u_out + uv_size;
    for (int row = 0; row < height / 2; row++) {
        memcpy(u_out + row * (width / 2), planes[1] + row * stride_uv, (size_t)(width / 2));
        memcpy(v_out + row * (width / 2), planes[2] + row * stride_uv, (size_t)(width / 2));
    }

    decoder->width = width;
    decoder->height = height;
    return 1;
}

int prism_dec_width(PrismDecoder *decoder) { return decoder ? decoder->width : 0; }

int prism_dec_height(PrismDecoder *decoder) { return decoder ? decoder->height : 0; }
