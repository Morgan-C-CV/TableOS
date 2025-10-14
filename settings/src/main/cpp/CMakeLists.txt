cmake_minimum_required(VERSION 3.10)
project(tableos_settings_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Expect OpenCV_DIR provided via Gradle arguments or environment
find_package(OpenCV REQUIRED)
include_directories(${OpenCV_INCLUDE_DIRS})

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../../../../cv_android_ndk_package/native)

add_library(projectioncards SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../cv_android_ndk_package/native/card_encoder_decoder.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../cv_android_ndk_package/native/card_encoder_decoder_c_api.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../cv_android_ndk_package/native/image_processing.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../cv_android_ndk_package/native/dot_card_detect.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../cv_android_ndk_package/native/detect_decode_api.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/bridge_jni.cpp)

target_link_libraries(projectioncards ${OpenCV_LIBS})