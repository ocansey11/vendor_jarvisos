#!/bin/bash
# Boots the emulator image built by vendor/jarvisos/tests/emulator/build.sh, headless.
# Run inside tmux: tmux new -d -s emu vendor/jarvisos/tests/emulator/run.sh    (add -wipe-data for a clean /data)
cd /srv/jarvis/lineage || exit 1
LOGS=${LOGS:-/srv/jarvis/logs}
# Shared with the team: files stay group-writable, one ccache for everyone.
umask 002
export CCACHE_DIR=${CCACHE_DIR:-/srv/jarvis/ccache} CCACHE_UMASK=002
# The paths breakfast would set, without breakfast: it touches files in out/
# with set times, which only their owner may do, so it fails for anyone but
# whoever built them (the server is shared).
TOP=$PWD
export ANDROID_BUILD_TOP=$TOP TARGET_PRODUCT=lineage_sdk_phone_x86_64
export ANDROID_PRODUCT_OUT=$TOP/out/target/product/emu64x OUT=$TOP/out/target/product/emu64x
export ANDROID_HOST_OUT=$TOP/out/host/linux-x86 ANDROID_SOONG_HOST_OUT=$TOP/out/host/linux-x86
export ANDROID_EMULATOR_PREBUILTS=$TOP/prebuilts/android-emulator/linux-x86_64
export PATH=$ANDROID_EMULATOR_PREBUILTS:$ANDROID_HOST_OUT/bin:$PATH
# 8 GB so a 2 GB model fits beside Android. `-cpu host` gives the guest the
# server's AVX2/FMA, which the llama.cpp engine needs (it refuses to load
# without them); it must stay last, everything after -qemu goes to QEMU.
exec emulator -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
    -memory 8192 -cores 4 -show-kernel "$@" -qemu -cpu host 2>&1 | tee $LOGS/emu-$(date +%Y%m%d-%H%M).log
