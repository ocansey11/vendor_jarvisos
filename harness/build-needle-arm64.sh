#!/bin/bash
# Compiles only the Needle JNI library for the phone (arm64), to prove it links.
# A Pong target: run it under the build lock, e.g.
#   tmux new -d -s build "flock -n /srv/jarvis/state/build.lock vendor/jarvisos/harness/build-needle-arm64.sh"
cd /srv/jarvis/lineage || exit 1
LOGS=${LOGS:-/srv/jarvis/logs}
export USE_CCACHE=1 CCACHE_EXEC=/usr/bin/ccache
source build/envsetup.sh >/dev/null
breakfast Pong userdebug >/dev/null 2>&1 || { echo "exit=1 breakfast failed" > "$LOGS/build-needle-arm64.summary"; exit 1; }
LOG=$LOGS/build-needle-arm64.log
m libneedle_jarvis > "$LOG" 2>&1
RC=$?
{ echo "exit=$RC product=$TARGET_PRODUCT finished=$(date -Is)"; grep -aE -A6 'error[: ]|FAILED:|ninja: build stopped' "$LOG" | cut -c1-400 | head -80; ls -la out/target/product/Pong/system/lib64/libneedle_jarvis.so 2>&1; } > "$LOGS/build-needle-arm64.summary"
exit $RC
