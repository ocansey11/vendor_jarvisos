# JarvisOS vendor overlay — product makefile
#
# Included by the device product makefile (e.g. lineage_pong.mk) via:
#   $(call inherit-product, vendor/jarvisos/jarvisos.mk)
#
# Places curated tool JSON definitions at /system/etc/jarvisos/tools/ on the
# device image. ToolScannerService reads them at boot from that path.

LOCAL_PATH := vendor/jarvisos

# ---------------------------------------------------------------------------
# Curated tool definitions
# ---------------------------------------------------------------------------

JARVISOS_TOOL_FILES := $(wildcard $(LOCAL_PATH)/tools/*.json)

PRODUCT_COPY_FILES += $(foreach f,$(JARVISOS_TOOL_FILES),\
    $(f):system/etc/jarvisos/tools/$(notdir $(f)))

# ---------------------------------------------------------------------------
# Native libraries loaded by system_server
#   libcactus        → vendor/cactus (CactusWrapper.java)
#   libllama_jarvis  → vendor/llama.cpp (LlamaEngine.java), with the small
#                      libllama_jarvis_cpucheck that decides whether to load it
#   libneedle_jarvis → vendor/jarvisos/native/needle (NeedleWrapper.java); the
#                      module is libneedle_jarvis on arm64 and
#                      libneedle_jarvis_emu on x86_64, same file either way
#   libobjectbox-jni → vendor/jarvisos/prebuilts/objectbox (JarvisStore.java)
# Nothing else depends on these modules, so they must be listed here to land
# in /system/lib64.
# ---------------------------------------------------------------------------

PRODUCT_PACKAGES += \
    libcactus \
    libllama_jarvis \
    libllama_jarvis_cpucheck \
    libobjectbox-jni

# The Needle engine has one prebuilt per architecture and so one module per
# architecture. A phone build fails on a package that does not exist for it,
# so only the right one is listed.
ifneq ($(filter %_x86_64,$(TARGET_PRODUCT)),)
PRODUCT_PACKAGES += libneedle_jarvis_emu
else
PRODUCT_PACKAGES += libneedle_jarvis
endif

# ---------------------------------------------------------------------------
# The Jarvis app (apps/Jarvis): chat and the tool list, on screen.
# ---------------------------------------------------------------------------

PRODUCT_PACKAGES += \
    Jarvis

# Products that enforce a generic /system (the emulator and Cuttlefish
# targets) need the JarvisOS additions listed explicitly.
PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST += \
    system/app/Jarvis/% \
    system/etc/jarvisos/tools/% \
    system/lib64/libcactus.so \
    system/lib64/libllama_jarvis.so \
    system/lib64/libllama_jarvis_cpucheck.so \
    system/lib64/libneedle_jarvis.so \
    system/lib64/libobjectbox-jni.so

# ---------------------------------------------------------------------------
# JarvisOS system service — services.jarvis is linked into services.jar via
# static_libs in frameworks/base/services/Android.bp; nothing to declare here.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# SELinux — service types for the "jarvis" and "jarvis_tools" binder services.
# This is a board-level list, but it is a plain make variable that every
# BoardConfig in this tree appends to, so setting it here (before BoardConfig
# is read) keeps the device tree untouched.
# ---------------------------------------------------------------------------

SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/jarvisos/sepolicy/private
