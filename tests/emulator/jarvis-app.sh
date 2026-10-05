#!/bin/bash
# End-to-end test of the Jarvis app on a running emulator, with no model:
# the app is part of the image and holds the service permission, its screens
# show what the service reports, a tool run from the Tools tab really runs,
# and a caller without the permission is refused.
#   vendor/jarvisos/tests/emulator/jarvis-app.sh
TOP="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
ADB="$TOP/out/host/linux-x86/bin/adb"
PKG=com.jarvisos.app
PERM=android.permission.ACCESS_JARVIS_SERVICE
PASS=0; FAIL=0

expect() {   # expect "what" "substring" "actual output"
    if [[ "$3" == *"$2"* ]]; then PASS=$((PASS+1)); echo "  pass  $1"
    else FAIL=$((FAIL+1)); echo "  FAIL  $1"; echo "        wanted: $2"; echo "        got:    ${3:0:300}"; fi
}
refuse() {   # refuse "what" "substring that must be absent" "actual output"
    if [[ "$3" != *"$2"* ]]; then PASS=$((PASS+1)); echo "  pass  $1"
    else FAIL=$((FAIL+1)); echo "  FAIL  $1"; echo "        must not contain: $2"; fi
}
# Everything on screen, as one line of XML.
screen() { "$ADB" shell uiautomator dump /sdcard/jarvis-ui.xml >/dev/null 2>&1; "$ADB" shell cat /sdcard/jarvis-ui.xml | tr -d '\r'; }
# Taps the centre of the first element whose text is exactly $1.
tap() {
    local xy
    xy="$(screen | grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 \
        | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/')"
    [ -n "$xy" ] || { echo "        (nothing on screen with text '$1')"; return 1; }
    set -- $xy
    "$ADB" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
    sleep 1
}

echo "Installed with the image"
# Not `pm path`: after an `adb install -r` update the running copy is in /data.
expect "app is part of the system image" "codePath=/system/app/Jarvis" "$("$ADB" shell dumpsys package $PKG)"
expect "permission is granted to it" "$PERM: granted=true" "$("$ADB" shell dumpsys package $PKG)"
expect "permission is signature-only" "prot=signature" "$("$ADB" shell dumpsys package permission $PERM | grep -A3 "Permission \[$PERM\]")"

echo "Callers without the permission"
# Transaction 1 is the first method of each interface; the shell does not hold
# the permission, so the reply must be an exception (first word ffffffff),
# not data.
expect "shell cannot list tools over Binder"  "ffffffff" "$("$ADB" shell service call jarvis_tools 1 | sed -n 2p)"
expect "shell cannot send a query over Binder" "ffffffff" "$("$ADB" shell service call jarvis 1 s16 hello | sed -n 2p)"
expect "cmd jarvis still works for the shell"  "ready: true" "$("$ADB" shell cmd jarvis status)"

echo "Status line"
"$ADB" shell am force-stop $PKG
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1
sleep 2
TOOLS="$("$ADB" shell cmd jarvis status | tr -d '\r' | sed -n 's/^tools: //p')"
UI="$(screen)"
expect "shows the service is ready"          "Service ready"       "$UI"
expect "tool count matches the service"      "$TOOLS tools ·"   "$UI"
expect "says honestly that no model is loaded" "no model loaded"   "$UI"
expect "chat explains why it cannot answer"  "No language model is loaded" "$UI"

echo "Tools tab"
tap "Tools"
# Apps' tools are listed first, so the built-in ones may be below the fold.
for i in 1 2 3 4 5 6; do
    UI="$(screen)"
    [[ "$UI" == *'text="get_battery_status"'* ]] && break
    "$ADB" shell input swipe 500 1500 500 900 300; sleep 1
done
expect "built-in tools are listed"  "get_battery_status"  "$UI"
expect "under their own heading"    "Built into JarvisOS" "$UI"
tap "get_battery_status"
tap "Run"
sleep 1
UI="$(screen)"
expect "running a tool shows its reply" "Battery: " "$UI"
refuse "and the reply is not an error"  "Error"     "$UI"
tap "Close"

echo "Chat tab"
tap "Chat"
tap "Ask Jarvis"
"$ADB" shell input text "hello"
tap "Send"
sleep 3
UI="$(screen)"
expect "the question appears in the conversation" 'text="hello"' "$UI"
# With no model the service's own words are shown, not a made-up answer.
expect "the service's reply is shown as is" "$("$ADB" shell "cmd jarvis query hello" | tr -d '\r')" "$UI"

echo
echo "$((PASS+FAIL)) checks, $FAIL failed"
[ $FAIL = 0 ]
