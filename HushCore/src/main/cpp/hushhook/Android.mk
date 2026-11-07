# HushHook：ART 层方法替换引擎（源码构建，不再使用预编译 .so）
HUSHHOOK_PATH := $(call my-dir)

# dex_builder：生成 hook 分派类所需的 dex 写入器（静态库）
include $(HUSHHOOK_PATH)/external/dex_builder/Android.mk

include $(CLEAR_VARS)
LOCAL_PATH              := $(HUSHHOOK_PATH)
LOCAL_MODULE            := hushhook
LOCAL_C_INCLUDES        := $(HUSHHOOK_PATH)/include \
                           $(HUSHHOOK_PATH)/external/dex_builder/include \
                           $(HUSHHOOK_PATH)/external/dex_builder/external/parallel_hashmap
LOCAL_CPPFLAGS          := -std=c++20 -Oz -fvisibility=hidden -fvisibility-inlines-hidden \
                           -fno-unwind-tables -fno-asynchronous-unwind-tables \
                           -Wno-gnu-string-literal-operator-template
LOCAL_SRC_FILES         := hushhook.cc
LOCAL_EXPORT_C_INCLUDES := $(HUSHHOOK_PATH)/include
LOCAL_STATIC_LIBRARIES  := dex_builder_static
LOCAL_LDLIBS            := -llog
LOCAL_CFLAGS            := -flto
LOCAL_LDFLAGS           := -flto -Wl,--gc-sections -Wl,--strip-all
include $(BUILD_SHARED_LIBRARY)
