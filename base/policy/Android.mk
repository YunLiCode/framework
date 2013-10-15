LOCAL_PATH:= $(call my-dir)

# the library
# ============================================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

##LEWA BEGIN
# csxie @ 2012/09/19: Lewa policy
include $(LOCAL_PATH)/../../../vendor/lewa/frameworks/base/policy/config.mk
##LEWA END

MTK_SERVICES_JAVA_PATH := ../../../mediatek/frameworks-ext/base/policy/java

LOCAL_SRC_FILES += $(call all-java-files-under,$(MTK_SERVICES_JAVA_PATH))
            
LOCAL_MODULE := android.policy

LOCAL_JAVA_LIBRARIES += mediatek-common mms-common

include $(BUILD_JAVA_LIBRARY)

# additionally, build unit tests in a separate .apk
include $(call all-makefiles-under,$(LOCAL_PATH))
