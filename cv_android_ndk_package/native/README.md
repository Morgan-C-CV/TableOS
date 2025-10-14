Android NDK package for Projection Cards

Overview
- This package bundles the C/C++ decoder and dot-card detection logic (OpenCV-based) for Android.
- You can copy `android_ndk_package/native` into your Android app under `app/src/main/cpp` and build a native library.

Contents
- Headers: `card_encoder_decoder.h`, `card_encoder_decoder_c_api.h`, `dot_card_detect.h`, `image_processing.h`, `detect_decode_api.h`
- Sources: `card_encoder_decoder.cpp`, `card_encoder_decoder_c_api.cpp`, `dot_card_detect.cpp`, `image_processing.cpp`, `detect_decode_api.cpp`
- Build: Minimal `CMakeLists.txt` for building `projectioncards` native library

Prerequisites
- Android NDK configured in your project (`android.defaultConfig.externalNativeBuild`)
- OpenCV Android SDK (download and unzip, set `OpenCV_DIR` in CMake or Gradle)

Integration Steps
1) Copy folder:
   - Move `android_ndk_package/native` to your app module: `app/src/main/cpp`

2) Connect CMake in `app/build.gradle`:
   - externalNativeBuild.cmake points to `src/main/cpp/CMakeLists.txt`
   - Ensure `abiFilters` cover your target ABIs

3) Provide OpenCV path:
   - Option A (Gradle): add `arguments "-DOpenCV_DIR=/path/to/OpenCV-android-sdk/sdk/native/jni"`
   - Option B (Local build): export `OpenCV_DIR` environment variable

4) JNI usage (example)
   - Create a JNI bridge file (Kotlin/Java) that loads `projectioncards` and calls C API:
     - `System.loadLibrary("projectioncards")`
     - native methods:
       - `int detectDecodeCardsNV21(byte[] nv21, int width, int height, int rotation, DetectedCard[] out)`

5) Frame conversion
   - If using Camera2 producing NV21, call `detect_decode_cards_nv21` which converts to BGR internally.
   - Or convert to ARGB8888 and then to BGR8 yourself; then call `detect_decode_cards_bgr8`.

Notes
- Colors mapping: 0=Red,1=Yellow,2=Green,3=Cyan,4=Blue,5=Indigo
- Marker bit order assumed: Left=bits 1,2; Top=bits 3,4
- The decoder auto-detects A/B group via `card_decode_encoding`