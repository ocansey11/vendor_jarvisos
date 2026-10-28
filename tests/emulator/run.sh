#!/bin/bash
# Boots the emulator image built by vendor/jarvisos/tests/emulator/build.sh, headless.
# Run inside tmux: tmux new -d -s emu vendor/jarvisos/tests/emulator/run.sh    (add -wipe-data for a clean /data)
cd /srv/jarvis/lineage || exit 1
LOGS=${LOGS:-/srv/jarvis/logs}
source build/envsetup.sh >/dev/null
breakfast sdk_phone_x86_64 userdebug >/dev/null 2>&1 || exit 1
# 8 GB so a 2 GB model fits beside Android. `-cpu host` gives the guest the
# server's AVX2/FMA, which the llama.cpp engine needs (it refuses to load
# without them); it must stay last, everything after -qemu goes to QEMU.
exec emulator -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
    -memory 8192 -cores 4 -show-kernel "$@" -qemu -cpu host 2>&1 | tee $LOGS/emu-$(date +%Y%m%d-%H%M).log
