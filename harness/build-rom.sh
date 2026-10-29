#!/bin/bash
# Full JarvisOS ROM build for Pong (Nothing Phone 2). Started by `jh build rom`.
cd /srv/jarvis/lineage || exit 1
LOGS=${LOGS:-/srv/jarvis/logs}
# Shared with the team: files stay group-writable, one ccache for everyone.
umask 002
export CCACHE_DIR=${CCACHE_DIR:-/srv/jarvis/ccache} CCACHE_UMASK=002
export USE_CCACHE=1 CCACHE_EXEC=/usr/bin/ccache
source build/envsetup.sh >/dev/null
breakfast Pong >/dev/null 2>&1 || { echo "exit=1 breakfast failed" > "$LOGS/build-jarvis-full.summary"; exit 1; }
LOG=$LOGS/build-jarvis-full-$(date +%Y%m%d-%H%M).log
time mka bacon 2>&1 | tee "$LOG"
RC=${PIPESTATUS[0]}
{
  echo "exit=$RC log=$LOG finished=$(date -Is)"
  grep -aE -A4 'error[: ]|FAILED:|^#### |ninja: build stopped|neverallow' "$LOG" | grep -v '^PWD=\|^rm -rf' | cut -c1-400 | head -200
  ls -la out/target/product/Pong/lineage-*.zip 2>/dev/null
} > "$LOGS/build-jarvis-full.summary"
exit $RC
