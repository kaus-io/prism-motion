#include "prism_codec.h"

#include <stdlib.h>
#include <string.h>

#include "codec_api.h"

struct PrismEncoder {
    ISVCEncoder *svc;
    int width;
    int height;
    int fps;
    int frame_index;
    uint8_t *i420;
};

static void rgba_to_i420(PrismEncoder *enc, const uint8_t *rgba) {
    const int w = enc->width;
    const int h = enc->height;
    uint8_t *y = enc->i420;
    uint8_t *u = y + w * h;
    uint8_t *v = u + (w / 2) * (h / 2);

    for (int j = 0; j < h; j++) {
        for (int i = 0; i < w; i++) {
            const uint8_t *p = rgba + (j * w + i) * 4;
            const int r = p[0], g = p[1], b = p[2];
            int yy = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            if (yy < 0) yy = 0;
            if (yy > 255) yy = 255;
            y[j * w + i] = (uint8_t)yy;
        }
    }

    for (int j = 0; j < h / 2; j++) {
        for (int i = 0; i < w / 2; i++) {
            const uint8_t *p = rgba + ((j * 2) * w + (i * 2)) * 4;
            const int r = p[0], g = p[1], b = p[2];
            int uu = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
            int vv = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
            if (uu < 0) uu = 0;
            if (uu > 255) uu = 255;
            if (vv < 0) vv = 0;
            if (vv > 255) vv = 255;
            u[j * (w / 2) + i] = (uint8_t)uu;
            v[j * (w / 2) + i] = (uint8_t)vv;
        }
    }
}

PrismEncoder *prism_enc_create(int width, int height, int fps, int bitrate_kbps) {
    if (width <= 0 || height <= 0 || (width % 2) != 0 || (height % 2) != 0) return NULL;

    PrismEncoder *enc = (PrismEncoder *)calloc(1, sizeof(PrismEncoder));
    if (!enc) return NULL;
    enc->width = width;
    enc->height = height;
    enc->fps = fps > 0 ? fps : 30;
    enc->i420 = (uint8_t *)malloc((size_t)width * height * 3 / 2);
    if (!enc->i420) {
        free(enc);
        return NULL;
    }

    if (WelsCreateSVCEncoder(&enc->svc) != 0 || !enc->svc) {
        free(enc->i420);
        free(enc);
        return NULL;
    }

    SEncParamExt param;
    memset(&param, 0, sizeof(param));
    (*enc->svc)->GetDefaultParams(enc->svc, &param);
    param.iUsageType = SCREEN_CONTENT_REAL_TIME;
    param.iPicWidth = width;
    param.iPicHeight = height;
    param.iTargetBitrate = bitrate_kbps * 1000;
    param.iRCMode = RC_BITRATE_MODE;
    param.fMaxFrameRate = (float)enc->fps;
    param.iTemporalLayerNum = 1;
    param.iSpatialLayerNum = 1;
    param.bEnableFrameSkip = false;
    param.bEnableDenoise = false;
    param.bEnableBackgroundDetection = false;
    param.bEnableAdaptiveQuant = false;
    param.bEnableSceneChangeDetect = true;
    param.bEnableLongTermReference = false;
    param.uiIntraPeriod = (unsigned int)(enc->fps * 2);
    param.eSpsPpsIdStrategy = CONSTANT_ID;
    param.bPrefixNalAddingCtrl = false;
    param.sSpatialLayers[0].iVideoWidth = width;
    param.sSpatialLayers[0].iVideoHeight = height;
    param.sSpatialLayers[0].fFrameRate = (float)enc->fps;
    param.sSpatialLayers[0].iSpatialBitrate = bitrate_kbps * 1000;
    param.sSpatialLayers[0].uiProfileIdc = PRO_BASELINE;
    param.sSpatialLayers[0].sSliceArgument.uiSliceMode = SM_SINGLE_SLICE;

    if ((*enc->svc)->InitializeExt(enc->svc, &param) != 0) {
        WelsDestroySVCEncoder(enc->svc);
        free(enc->i420);
        free(enc);
        return NULL;
    }

    int video_format = videoFormatI420;
    (*enc->svc)->SetOption(enc->svc, ENCODER_OPTION_DATAFORMAT, &video_format);
    return enc;
}

int prism_enc_encode(PrismEncoder *enc, const uint8_t *rgba, uint8_t *out, int out_capacity, int *keyframe) {
    if (!enc || !rgba || !out || out_capacity <= 0) return -1;
    rgba_to_i420(enc, rgba);

    const int w = enc->width;
    const int h = enc->height;

    SSourcePicture pic;
    memset(&pic, 0, sizeof(pic));
    pic.iColorFormat = videoFormatI420;
    pic.iPicWidth = w;
    pic.iPicHeight = h;
    pic.iStride[0] = w;
    pic.iStride[1] = w / 2;
    pic.iStride[2] = w / 2;
    pic.pData[0] = enc->i420;
    pic.pData[1] = enc->i420 + w * h;
    pic.pData[2] = enc->i420 + w * h + (w / 2) * (h / 2);
    pic.uiTimeStamp = (long long)enc->frame_index * 1000 / enc->fps;
    enc->frame_index++;

    SFrameBSInfo info;
    memset(&info, 0, sizeof(info));
    if ((*enc->svc)->EncodeFrame(enc->svc, &pic, &info) != 0) return -1;
    if (info.eFrameType == videoFrameTypeSkip) return 0;

    int written = 0;
    for (int layer = 0; layer < info.iLayerNum; layer++) {
        const SLayerBSInfo *layer_info = &info.sLayerInfo[layer];
        int layer_size = 0;
        for (int nal = 0; nal < layer_info->iNalCount; nal++) {
            layer_size += layer_info->pNalLengthInByte[nal];
        }
        if (written + layer_size > out_capacity) return -1;
        memcpy(out + written, layer_info->pBsBuf, (size_t)layer_size);
        written += layer_size;
    }

    if (keyframe) {
        *keyframe = (info.eFrameType == videoFrameTypeIDR || info.eFrameType == videoFrameTypeI) ? 1 : 0;
    }
    return written;
}

void prism_enc_destroy(PrismEncoder *enc) {
    if (!enc) return;
    if (enc->svc) {
        (*enc->svc)->Uninitialize(enc->svc);
        WelsDestroySVCEncoder(enc->svc);
    }
    free(enc->i420);
    free(enc);
}
