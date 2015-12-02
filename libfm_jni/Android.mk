ifeq ($(BOARD_USES_QCOM_HARDWARE),true)
ifneq (,$(filter $(QCOM_BOARD_PLATFORMS),$(TARGET_BOARD_PLATFORM)))
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    FmIoctlsInterface.cpp \
    ConfigFmThs.cpp \
    FmPerformanceParams.cpp \
    ConfFileParser.cpp \
    FmRadioController.cpp \
    LibfmJni.cpp

LOCAL_C_INCLUDES := $(JNI_H_INCLUDE) \
    frameworks/base/include/media

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libdl \
    libmedia \
    libnativehelper \

#LOCAL_C_INCLUDES += $(TARGET_OUT_INTERMEDIATES)/KERNEL_OBJ/usr/include
#LOCAL_ADDITIONAL_DEPENDENCIES := $(TARGET_OUT_INTERMEDIATES)/KERNEL_OBJ/usr

ifeq ($(TARGET_FM_LEGACY_PATCHLOADER),true)
    LOCAL_CFLAGS += -DFM_LEGACY_PATCHLOADER
endif

ifeq ($(TARGET_QCOM_NO_FM_FIRMWARE),true)
    LOCAL_CFLAGS += -DQCOM_NO_FM_FIRMWARE
endif

LOCAL_MODULE := libfmjni
include $(BUILD_SHARED_LIBRARY)

endif

endif # QCOM_HARDWARE
