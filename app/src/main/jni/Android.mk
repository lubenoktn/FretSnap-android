LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := usbaudio
LOCAL_SRC_FILES := usbaudio.c
LOCAL_CFLAGS := -Wall -Werror
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
