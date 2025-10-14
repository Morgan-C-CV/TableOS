#include <jni.h>
#include <vector>
#include "detect_decode_api.h"

extern "C" JNIEXPORT jintArray JNICALL
Java_com_tableos_settings_ProjectionCardsBridge_detectDecodeNv21(
        JNIEnv* env, jobject /*thiz*/, jbyteArray nv21, jint width, jint height, jint max_cards) {
    jbyte* data = env->GetByteArrayElements(nv21, nullptr);
    DetectedCard* cards = new DetectedCard[max_cards];
    int count = 0;
    if (data) {
        count = detect_decode_cards_nv21(reinterpret_cast<const unsigned char*>(data), width, height, cards, max_cards);
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
    delete[] cards;
    return result;
}