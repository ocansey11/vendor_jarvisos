#!/bin/bash
# End-to-end test of the fast path and voice on a running emulator: a typed and
# a spoken request must switch the torch through Needle and Whistle alone, the
# camera service must confirm the change, and anything Needle is unsure of
# must still reach the chat model.
#   vendor/jarvisos/tests/emulator/voice.sh
# Needs the four models installed (cmd jarvis install-model chat|embed|needle|whistle)
# and spoken clips in $CLIPS (default /srv/jarvis/models/voice-clips): flash-on-*.wav,
# flash-off-*.wav. Any 16-bit PCM WAV will do; ours were made with piper.
TOP="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
ADB="$TOP/out/host/linux-x86/bin/adb"
CLIPS="${CLIPS:-/srv/jarvis/models/voice-clips}"
PASS=0; FAIL=0

expect() {   # expect "what" "substring" "actual output"
    if [[ "$3" == *"$2"* ]]; then PASS=$((PASS+1)); echo "  pass  $1"
    else FAIL=$((FAIL+1)); echo "  FAIL  $1"; echo "        wanted: $2"; echo "        got:    $3"; fi
}
under() {    # under "what" limit_ms actual_ms
    if [ "${3:-999999}" -le "$2" ]; then PASS=$((PASS+1)); echo "  pass  $1 ($3 ms)"
    else FAIL=$((FAIL+1)); echo "  FAIL  $1: $3 ms, limit $2 ms"; fi
}
jarvis() { "$ADB" shell "cmd jarvis $*" 2>&1 | tr -d '\r'; }
voice()  { "$ADB" shell -T cmd jarvis voice < "$1" 2>&1 | tr -d '\r'; }
field()  { sed -n "s/^$1: //p" <<<"$2"; }
# What the camera service last did with the torch: "on" or "off".
torch()  { "$ADB" shell dumpsys media.camera | grep -o 'Torch for camera id [0-9]* turned [a-z]*' | head -1 | awk '{print $NF}'; }
timed()  { local t0=$(date +%s%N); OUT="$("$@")"; MS=$(( ($(date +%s%N) - t0) / 1000000 )); }

STATUS="$(jarvis status)"
expect "service ready"           "ready: true" "$STATUS"
expect "Needle and Whistle loaded" "needle: tool calling ready, speech ready" "$STATUS"
expect "set_flashlight is a registered tool" "set_flashlight" "$(jarvis tools)"
jarvis fast on >/dev/null

echo "Typed request"
jarvis "tool set_flashlight '{\"enabled\":false}'" >/dev/null
OUT="$(jarvis "route 'turn on the flashlight'")"
expect "dry run names the tool"        'set_flashlight {"enabled":true}' "$OUT"
expect "dry run leaves the torch alone" "off" "$(torch)"
timed jarvis "query 'turn on the flashlight'"
expect "typed: reply"                  "Flashlight on" "$OUT"
expect "typed: camera service says on" "on" "$(torch)"
under  "typed: round trip from the host" 1500 "$MS"

echo "Spoken request"
for clip in "$CLIPS"/flash-off-*.wav "$CLIPS"/flash-on-*.wav; do
    [ -f "$clip" ] || { echo "  no clips in $CLIPS"; FAIL=$((FAIL+1)); break; }
    name="$(basename "$clip" .wav)"; want=on; [[ "$name" == flash-off-* ]] && want=off
    OUT="$(voice "$clip")"
    if [[ "$name" == *-espeak ]]; then
        # A robotic synthesiser, kept as a hard case: reported, not judged.
        echo "  info  $name: heard \"$(field heard "$OUT")\", path $(field path "$OUT"), torch $(torch)"
        continue
    fi
    expect "$name: heard"         "the flashlight" "$(field heard "$OUT" | tr 'A-Z' 'a-z')"
    expect "$name: fast path"     "fast"           "$(field path "$OUT")"
    expect "$name: reply"         "Flashlight $want" "$(field reply "$OUT")"
    expect "$name: camera service says $want" "$want" "$(torch)"
    under  "$name: inside the service" 1000 "$(field 'total ms' "$OUT")"
done

echo "What the fast path must leave alone"
OUT="$(jarvis "route 'what is the capital of France'")"
CONF="$(field confidence "$OUT")"
if awk "BEGIN{exit !($CONF < 0.75)}"; then PASS=$((PASS+1)); echo "  pass  off-topic request is below the confidence bar ($CONF)"
else FAIL=$((FAIL+1)); echo "  FAIL  off-topic request scored $CONF"; fi
if [ "$1" = "--slow" ]; then
    timed jarvis "query 'what is the capital of France'"
    expect "off-topic request is answered by the chat model" "Paris" "$OUT"
    echo "        chat model took $MS ms"
    jarvis fast off >/dev/null
    timed jarvis "query 'turn off the flashlight'"
    jarvis fast on >/dev/null
    expect "with the fast path off the chat model still switches the torch" "off" "$(torch)"
    echo "        same request without the fast path: $MS ms"
fi

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
