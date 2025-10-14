// Prefer NDK jni.h; if the editor lacks NDK includes, provide minimal fallbacks
#if __has_include(<jni.h>)
#include <jni.h>
#else
#include <stdint.h>
#ifndef JNIEXPORT
#define JNIEXPORT
#endif
#ifndef JNICALL
#define JNICALL
#endif
typedef int jint;
typedef void* jobject;
typedef void* jbyteArray;
typedef void* jintArray;
#ifndef JNI_ABORT
#define JNI_ABORT 0
#endif
struct JNINativeInterface_;
typedef const struct JNINativeInterface_* JNIEnv;
#endif

#include <vector>
#include <exception>
#include "detect_decode_api.h"

extern "C" JNIEXPORT jintArray JNICALL
Java_com_tableos_settings_ProjectionCardsBridge_detectDecodeNv21(
        JNIEnv* env, jobject /*thiz*/, jbyteArray nv21, jint width, jint height, jint max_cards) {
    jbyte* data = env->GetByteArrayElements(nv21, nullptr);
    DetectedCard* cards = (max_cards > 0 ? new DetectedCard[max_cards] : nullptr);
    int count = 0;
    try {
        if (data && cards && max_cards > 0 && width > 0 && height > 0) {
            // OpenCV 某些转换要求偶数尺寸，必要时在本地层做降一调整
            int w = width - (width & 1);
            int h = height - (height & 1);
            if (w <= 0 || h <= 0) {
                w = width;
                h = height;
            }
            count = detect_decode_cards_nv21(reinterpret_cast<const unsigned char*>(data), w, h, cards, max_cards);
        }
    } catch (const std::exception& /*e*/) {
        count = 0;
    } catch (...) {
        count = 0;
    }
    int out_len = 1 + (count > 0 ? count * 6 : 0);
    std::vector<jint> tmp(out_len);
    tmp[0] = count;
    for (int i = 0; i < count; ++i) {
        int base = 1 + i * 6;
        tmp[base + 0] = cards[i].card_id;
        tmp[base + 1] = cards[i].group_type;
        tmp[base + 2] = cards[i].tl_x;
        tmp[base + 3] = cards[i].tl_y;
        tmp[base + 4] = cards[i].br_x;
        tmp[base + 5] = cards[i].br_y;
    }
    jintArray result = env->NewIntArray(out_len);
    if (result) {
        env->SetIntArrayRegion(result, 0, out_len, tmp.data());
    }
    if (data) env->ReleaseByteArrayElements(nv21, data, JNI_ABORT);
    if (cards) delete[] cards;
    return result;
}