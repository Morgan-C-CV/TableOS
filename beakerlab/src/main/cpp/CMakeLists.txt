cmake_minimum_required(VERSION 3.18.1)

project("beakerlab_jni")

# Add the shape_recognition_ndk library path
set(SHAPE_RECOGNITION_NDK_PATH "${CMAKE_CURRENT_SOURCE_DIR}/../../../../shape_recognition_ndk/native")

# Include shape_recognition_ndk headers
include_directories(${SHAPE_RECOGNITION_NDK_PATH})

# First build the shape_recognition_ndk library
add_subdirectory(${SHAPE_RECOGNITION_NDK_PATH} shape_recognition_ndk_build)

# Create our JNI library
add_library(
    beakerlab_jni
    SHARED
    shape_detector_jni.cpp
)

# Include directories
target_include_directories(beakerlab_jni PRIVATE
    ${SHAPE_RECOGNITION_NDK_PATH}
)

# Link libraries
target_link_libraries(
    beakerlab_jni
    shape_detector_ndk
    android
    log
    jnigraphics
)

# Set C++ standard
set_property(TARGET beakerlab_jni PROPERTY CXX_STANDARD 17)
set_property(TARGET beakerlab_jni PROPERTY CXX_STANDARD_REQUIRED ON)

# Set compiler flags
target_compile_options(beakerlab_jni PRIVATE
    -Wall
    -Wextra
    -O2
    -fPIC
)