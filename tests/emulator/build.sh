#!/bin/bash
# JarvisOS build for the Android emulator (lineage_sdk_phone_x86_64).
# Run inside tmux: tmux new -d -s build "vendor/jarvisos/tests/emulator/build.sh [targets...]"   (default target: full image)
cd ~/android/lineage || exit 1
export USE_CCACHE=1 CCACHE_EXEC=/usr/bin/ccache
source build/envsetup.sh >/dev/null
breakfast sdk_phone_x86_64 userdebug >/dev/null 2>&1 || { echo "exit=1 breakfast failed" > ~/build-jarvis-emu.summary; exit 1; }
LOG=~/build-jarvis-emu-$(date +%Y%m%d-%H%M).log
time m "$@" 2>&1 | tee "$LOG"
RC=${PIPESTATUS[0]}
{
  echo "exit=$RC log=$LOG targets='$*' product=$TARGET_PRODUCT finished=$(date -Is)"
  grep -aE -A6 'error[: ]|FAILED:|^#### |ninja: build stopped|neverallow' "$LOG" | grep -v '^PWD=\|^rm -rf' | cut -c1-400 | head -200
} > ~/build-jarvis-emu.summary
exit $RC
