#!/bin/bash
# Boots the emulator image built by vendor/jarvisos/tests/emulator/build.sh, headless.
# Run inside tmux: tmux new -d -s emu vendor/jarvisos/tests/emulator/run.sh    (add -wipe-data for a clean /data)
cd ~/android/lineage || exit 1
source build/envsetup.sh >/dev/null
breakfast sdk_phone_x86_64 userdebug >/dev/null 2>&1 || exit 1
exec emulator -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
    -memory 4096 -cores 4 -show-kernel "$@" 2>&1 | tee ~/emu-$(date +%Y%m%d-%H%M).log
