#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/bitmap.h>
#include "shape_detector_c_api.h"

#define LOG_TAG "ShapeDetectorJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_tableos_beakerlab_ShapeDetectorJNI_init(JNIEnv *env, jclass clazz) {
    LOGI("Initializing shape detector");
    bool result = shape_detector_init();
    if (result) {
        LOGI("Shape detector initialized successfully");
    } else {
        LOGE("Failed to initialize shape detector");
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_tableos_beakerlab_ShapeDetectorJNI_cleanup(JNIEnv *env, jclass clazz) {
    LOGI("Cleaning up shape detector");
    shape_detector_cleanup();
}

JNIEXPORT jstring JNICALL
Java_com_tableos_beakerlab_ShapeDetectorJNI_detectShapesFromBitmap(JNIEnv *env, jclass clazz, jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels;
    
    // Get bitmap info
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("Failed to get bitmap info");
        return env->NewStringUTF("{}");
    }
    
    // Lock bitmap pixels
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        return env->NewStringUTF("{}");
    }
    
    // Convert RGBA to BGR for OpenCV
    int width = info.width;
    int height = info.height;
    uint8_t* bgr_data = new uint8_t[width * height * 3];
    
    uint8_t* rgba_pixels = (uint8_t*)pixels;
    for (int i = 0; i < width * height; i++) {
        bgr_data[i * 3 + 0] = rgba_pixels[i * 4 + 2]; // B
        bgr_data[i * 3 + 1] = rgba_pixels[i * 4 + 1]; // G
        bgr_data[i * 3 + 2] = rgba_pixels[i * 4 + 0]; // R
    }
    
    // Unlock bitmap
    AndroidBitmap_unlockPixels(env, bitmap);
    
    // Create ImageData structure
    ImageData image_data;
    image_data.data = bgr_data;
    image_data.width = width;
    image_data.height = height;
    image_data.channels = 3;
    
    // Detect shapes
    LOGI("Detecting shapes in image %dx%d", width, height);
    DetectionResult* result = shape_detector_detect(&image_data, true);
    
    jstring json_result;
    if (result) {
        char* json_str = shape_detector_generate_json(result);
        if (json_str) {
            LOGI("Detection result: %s", json_str);
            json_result = env->NewStringUTF(json_str);
            shape_detector_free_json(json_str);
        } else {
            LOGE("Failed to generate JSON");
            json_result = env->NewStringUTF("{}");
        }
        shape_detector_free_result(result);
    } else {
        LOGE("Detection failed");
        json_result = env->NewStringUTF("{}");
    }
    
    // Clean up
    delete[] bgr_data;
    
    return json_result;
}

JNIEXPORT jobject JNICALL
Java_com_tableos_beakerlab_ShapeDetectorJNI_annotateImage(JNIEnv *env, jclass clazz, jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels;
    
    // Get bitmap info
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("Failed to get bitmap info");
        return nullptr;
    }
    
    // Lock bitmap pixels
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        return nullptr;
    }
    
    // Convert RGBA to BGR for OpenCV
    int width = info.width;
    int height = info.height;
    uint8_t* bgr_data = new uint8_t[width * height * 3];
    
    uint8_t* rgba_pixels = (uint8_t*)pixels;
    for (int i = 0; i < width * height; i++) {
        bgr_data[i * 3 + 0] = rgba_pixels[i * 4 + 2]; // B
        bgr_data[i * 3 + 1] = rgba_pixels[i * 4 + 1]; // G
        bgr_data[i * 3 + 2] = rgba_pixels[i * 4 + 0]; // R
    }
    
    // Unlock bitmap
    AndroidBitmap_unlockPixels(env, bitmap);
    
    // Create ImageData structure
    ImageData image_data;
    image_data.data = bgr_data;
    image_data.width = width;
    image_data.height = height;
    image_data.channels = 3;
    
    // Detect shapes
    DetectionResult* result = shape_detector_detect(&image_data, true);
    
    jobject annotated_bitmap = nullptr;
    if (result) {
        // Annotate image
        ImageData output_data;
        if (shape_detector_annotate_image(&image_data, result, &output_data)) {
            // Create new bitmap
            jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
            jmethodID createBitmapMethod = env->GetStaticMethodID(bitmapClass, "createBitmap", 
                "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
            
            jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
            jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888", 
                "Landroid/graphics/Bitmap$Config;");
            jobject config = env->GetStaticObjectField(configClass, argb8888Field);
            
            annotated_bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, 
                width, height, config);
            
            // Copy annotated data to bitmap
            void* new_pixels;
            if (AndroidBitmap_lockPixels(env, annotated_bitmap, &new_pixels) >= 0) {
                uint8_t* new_rgba_pixels = (uint8_t*)new_pixels;
                for (int i = 0; i < width * height; i++) {
                    new_rgba_pixels[i * 4 + 0] = output_data.data[i * 3 + 2]; // R
                    new_rgba_pixels[i * 4 + 1] = output_data.data[i * 3 + 1]; // G
                    new_rgba_pixels[i * 4 + 2] = output_data.data[i * 3 + 0]; // B
                    new_rgba_pixels[i * 4 + 3] = 255; // A
                }
                AndroidBitmap_unlockPixels(env, annotated_bitmap);
            }
            
            shape_detector_free_image(&output_data);
        }
        shape_detector_free_result(result);
    }
    
    // Clean up
    delete[] bgr_data;
    
    return annotated_bitmap;
}

JNIEXPORT jstring JNICALL
Java_com_tableos_beakerlab_ShapeDetectorJNI_getVersion(JNIEnv *env, jclass clazz) {
    const char* version = shape_detector_get_version();
    return env->NewStringUTF(version);
}

} // extern "C"