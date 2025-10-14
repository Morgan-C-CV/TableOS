cmake_minimum_required(VERSION 3.10)
project(tableos_settings_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Expect OpenCV_DIR provided via Gradle arguments or environment
find_package(OpenCV REQUIRED)
include_directories(${OpenCV_INCLUDE_DIRS})

# Add CV package as subdirectory, but exclude CLI tools
set(BUILD_CLI_TOOLS OFF CACHE BOOL "Build CLI tools" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../cv_android_ndk_package/native cv_package)

# Create our JNI bridge library
add_library(tableos_settings_native SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/bridge_jni.cpp)

# Link with the projectioncards library from CV package
target_link_libraries(tableos_settings_native 
        projectioncards 
        ${OpenCV_LIBS})