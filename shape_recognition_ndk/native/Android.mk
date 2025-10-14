LOCAL_PATH := $(call my-dir)

# Clear variables
include $(CLEAR_VARS)

# OpenCV module
OPENCV_CAMERA_MODULES := off
OPENCV_INSTALL_MODULES := on
OPENCV_LIB_TYPE := SHARED

# Include OpenCV
ifeq ("$(wildcard $(OPENCV_ANDROID_SDK)/OpenCV.mk)","")
    # Try to find OpenCV in common locations
    ifneq ("$(wildcard $(OPENCV_ANDROID_SDK)/sdk/native/jni/OpenCV.mk)","")
        include $(OPENCV_ANDROID_SDK)/sdk/native/jni/OpenCV.mk
    else
        $(error OpenCV Android SDK path is not specified correctly. Please set OPENCV_ANDROID_SDK)
    endif
else
    include $(OPENCV_ANDROID_SDK)/OpenCV.mk
endif

# Shape detector module
include $(CLEAR_VARS)

LOCAL_MODULE := shape_detector_ndk
LOCAL_SRC_FILES := \
    shape_detector.cpp \
    shape_detector_c_api.cpp

LOCAL_C_INCLUDES += $(LOCAL_PATH)
LOCAL_CFLAGS := -DANDROID_NDK -Wall -Wextra -O2 -fPIC
LOCAL_CPPFLAGS := -std=c++17 -frtti -fexceptions
LOCAL_LDLIBS := -llog -ljnigraphics -lz
LOCAL_STATIC_LIBRARIES := 
LOCAL_SHARED_LIBRARIES := opencv_java4

include $(BUILD_SHARED_LIBRARY)