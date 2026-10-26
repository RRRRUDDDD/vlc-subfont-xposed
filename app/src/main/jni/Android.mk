LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := subfontpatch
LOCAL_SRC_FILES := subfontpatch.c
LOCAL_CFLAGS := -std=c11 -O2
include $(BUILD_SHARED_LIBRARY)
