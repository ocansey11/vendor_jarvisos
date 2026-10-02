#!/bin/bash
# End-to-end test of the app-tool path on a running emulator, with no model:
# install the sample app, check Jarvis discovers its tools, call them through
# `cmd jarvis tool`, and check that repeating a request changes nothing.
#   vendor/jarvisos/tests/emulator/app-tools.sh        (after: m JarvisAlarmDemo)
TOP="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
ADB="$TOP/out/host/linux-x86/bin/adb"
APK="$(ls "$TOP"/out/target/product/*/system/app/JarvisAlarmDemo/JarvisAlarmDemo.apk 2>/dev/null | head -1)"
PKG=com.jarvisos.demo.alarms
PASS=0; FAIL=0

expect() {   # expect "what" "substring" "actual output"
    if [[ "$3" == *"$2"* ]]; then PASS=$((PASS+1)); echo "  pass  $1"
    else FAIL=$((FAIL+1)); echo "  FAIL  $1"; echo "        wanted: $2"; echo "        got:    $3"; fi
}
refuse() {   # refuse "what" "substring that must be absent" "actual output"
    if [[ "$3" != *"$2"* ]]; then PASS=$((PASS+1)); echo "  pass  $1"
    else FAIL=$((FAIL+1)); echo "  FAIL  $1"; echo "        must not contain: $2"; fi
}
# The whole command is one string so the device's shell sees the JSON quoted.
tool() { "$ADB" shell "cmd jarvis tool $1 '${2:-}'" 2>&1 | tr -d '\r'; }

[ -f "$APK" ] || { echo "JarvisAlarmDemo.apk not built (m JarvisAlarmDemo)"; exit 2; }
"$ADB" uninstall $PKG >/dev/null 2>&1

echo "Discovery"
refuse "tools absent before install" "demo_set_alarm" "$("$ADB" shell cmd jarvis tools)"
"$ADB" install -r "$APK" >/dev/null 2>&1 || { echo "install failed"; exit 2; }
for i in 1 2 3 4 5 6 7 8 9 10; do
    "$ADB" shell cmd jarvis tools | grep -q demo_set_alarm && break; sleep 1
done
TOOLS="$("$ADB" shell cmd jarvis tools)"
expect "demo_list_alarms registered after install"  "demo_list_alarms  [$PKG/"  "$TOOLS"
expect "demo_set_alarm registered after install"    "demo_set_alarm  [$PKG/"    "$TOOLS"
expect "demo_delete_alarm registered after install" "demo_delete_alarm  [$PKG/" "$TOOLS"
expect "parameter schema read from the app" 'hour' "$("$ADB" shell cmd jarvis describe demo_set_alarm)"

echo "Calling tools"
expect "list on a fresh install is empty"   "No alarms are set."            "$(tool demo_list_alarms)"
expect "set 07:00 creates the alarm"        "Alarm set for 07:00."          "$(tool demo_set_alarm '{"hour":7,"minute":0,"label":"gym"}')"
expect "list shows the new alarm and label" "07:00 (gym)"                   "$(tool demo_list_alarms)"

echo "Doing it twice"
expect "second set 07:00 reports it exists" "already exists"                "$(tool demo_set_alarm '{"hour":7,"minute":0}')"
expect "still exactly one alarm"            "1 alarm(s) set"                "$(tool demo_list_alarms)"
expect "a different time is still accepted" "Alarm set for 08:30."          "$(tool demo_set_alarm '{"hour":8,"minute":30}')"
expect "now two alarms"                     "2 alarm(s) set"                "$(tool demo_list_alarms)"

echo "Undoing by hand, then asking again"
expect "delete 07:00"                       "Alarm for 07:00 deleted."      "$(tool demo_delete_alarm '{"hour":7,"minute":0}')"
expect "second delete reports nothing there" "There is no alarm for 07:00"  "$(tool demo_delete_alarm '{"hour":7,"minute":0}')"
expect "set 07:00 works again after delete" "Alarm set for 07:00."          "$(tool demo_set_alarm '{"hour":7,"minute":0}')"

echo "Bad input"
expect "hour 25 is rejected"                "must be between 0 and 23"      "$(tool demo_set_alarm '{"hour":25,"minute":0}')"
expect "missing minute is rejected"         "missing argument 'minute'"     "$(tool demo_set_alarm '{"hour":9}')"
expect "unknown tool is reported"           "not registered"                "$(tool demo_no_such_tool)"
expect "broken JSON is rejected, not run"   "not valid JSON"                "$(tool demo_set_alarm '{hour:9')"

echo "Only the system may call tools"
SPOOF="$("$ADB" shell "am broadcast -a com.jarvisos.TOOL -n $PKG/.SetAlarmTool --es com.jarvisos.tool.arg.hour 9 --es com.jarvisos.tool.arg.minute 9" 2>&1)"
if "$ADB" shell id | grep -q 'uid=0'; then
    echo "  skip  adb is running as root, which is allowed to send it"
else
    expect "a non-system sender is refused"  "Permission Denial"             "$SPOOF"
    refuse "and no alarm was created by it"  "09:09"                         "$(tool demo_list_alarms)"
fi

echo "Removal"
"$ADB" uninstall $PKG >/dev/null 2>&1
for i in 1 2 3 4 5 6 7 8 9 10; do
    "$ADB" shell cmd jarvis tools | grep -q demo_set_alarm || break; sleep 1
done
refuse "tools gone after uninstall" "demo_" "$("$ADB" shell cmd jarvis tools)"

echo
echo "$((PASS+FAIL)) checks, $FAIL failed"
[ $FAIL = 0 ]
