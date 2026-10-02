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
#   libobjectbox-jni → vendor/jarvisos/prebuilts/objectbox (JarvisStore.java)
# Nothing else depends on these modules, so they must be listed here to land
# in /system/lib64.
# ---------------------------------------------------------------------------

PRODUCT_PACKAGES += \
    libcactus \
    libobjectbox-jni

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
