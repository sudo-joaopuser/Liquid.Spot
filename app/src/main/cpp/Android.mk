LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := liquidspotaudio
LOCAL_SRC_FILES := liquidspot_audio.cpp
LOCAL_CPPFLAGS := -std=c++20 -fvisibility=hidden -fno-exceptions -fno-rtti
LOCAL_LDLIBS := -ldl -llog
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
