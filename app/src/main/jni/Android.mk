LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := connect4o6Byr
LOCAL_SRC_FILES := connect4o6Byr.c
LOCAL_LDLIBS        := -L$(SYSROOT)/usr/lib -llog
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include-all
LOCAL_STATIC_LIBRARIES +=  libstlport
LOCAL_C_INCLUDES += external/stlport/stlport
include $(BUILD_SHARED_LIBRARY)






