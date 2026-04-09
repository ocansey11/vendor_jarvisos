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
# JarvisOS system service — included in the services build via Android.bp.
# Declared here for documentation; Soong picks it up automatically.
# services.jarvis → frameworks/base/services/core/java/com/android/server/jarvis/
# ---------------------------------------------------------------------------
