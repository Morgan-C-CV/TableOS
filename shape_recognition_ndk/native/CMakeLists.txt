cmake_minimum_required(VERSION 3.18.1)

# Declares and names the project.
project("shape_detector_ndk")

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.

# Find required packages
find_package(OpenCV REQUIRED)

# Add the shape detector library
add_library(
    # Sets the name of the library.
    shape_detector_ndk

    # Sets the library as a shared library.
    SHARED

    # Provides a relative path to your source file(s).
    shape_detector.cpp
    shape_detector_c_api.cpp
)

# Include directories
target_include_directories(shape_detector_ndk PRIVATE
    ${OpenCV_INCLUDE_DIRS}
)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
    shape_detector_ndk

    # OpenCV libraries
    ${OpenCV_LIBS}
)

# Set C++ standard
set_property(TARGET shape_detector_ndk PROPERTY CXX_STANDARD 17)
set_property(TARGET shape_detector_ndk PROPERTY CXX_STANDARD_REQUIRED ON)

# Set compiler flags
target_compile_options(shape_detector_ndk PRIVATE
    -Wall
    -Wextra
    -O2
    -fPIC
)