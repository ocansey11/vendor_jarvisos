#!/bin/bash
# Puts one chat model through the same requests on a running emulator, with the
# fast path off so every request reaches the chat model. For each request it
# records the tool the model called, its arguments, what the tool returned, the
# reply and the time. "ok" means the right tool; check the arguments by eye.
#   vendor/jarvisos/tests/emulator/compare-models.sh ~/jarvis-models/<model>.gguf
# Writes ~/model-compare-<model>.tsv. Run it once per model and compare the files.
# The tools really run (torch, alarm, volume...); none of the requests sends a
# message or makes a call.
TOP="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
ADB="$TOP/out/host/linux-x86/bin/adb"
MODEL="${1:?usage: compare-models.sh model.gguf}"
NAME="$(basename "$MODEL" .gguf)"
OUTFILE="$HOME/model-compare-$NAME.tsv"

jarvis() { "$ADB" shell "cmd jarvis $*" </dev/null 2>&1 | tr -d '\r'; }

# request <TAB> the tool a correct answer calls ("none" for a plain answer)
REQUESTS="turn on the flashlight	set_flashlight
turn off the flashlight	set_flashlight
set an alarm for 7:30	set_alarm
how much battery do I have left	get_battery_status
set the volume to 50 percent	set_volume
turn on do not disturb	set_dnd
turn off do not disturb	set_dnd
set the screen brightness to 80 percent	set_brightness
open the settings app	open_app
pause the music	media_control
what is the capital of France	none
explain in one sentence what a black hole is	none"

echo "Installing $NAME as the chat model"
if [ -z "$SKIP_INSTALL" ]; then   # SKIP_INSTALL=1 when the model is already in place
    "$ADB" shell -T cmd jarvis install-model chat < "$MODEL" | tr -d '\r'
    jarvis reload
fi
jarvis fast off >/dev/null

printf 'request\twanted\tcalled\tms\targs\tresult\treply\n' > "$OUTFILE"
RIGHT=0; TOTAL=0
while IFS=$'\t' read -r request wanted; do
    "$ADB" logcat -c </dev/null
    t0=$(date +%s%N)
    reply="$(jarvis "query '$request'" | tr '\n\t' '  ')"
    ms=$(( ($(date +%s%N) - t0) / 1000000 ))
    log="$("$ADB" logcat -d -s ToolNode </dev/null | tr -d '\r')"
    called="$(sed -n 's/.*Dispatching tool: \([a-z_]*\).*/\1/p' <<<"$log" | sort -u | paste -sd, -)"
    args="$(sed -n 's/.*Dispatching tool: [a-z_]* args=//p' <<<"$log" | paste -sd' ' -)"
    result="$(sed -n 's/.*Tool result: //p' <<<"$log" | paste -sd' ' -)"
    called="${called:-none}"
    TOTAL=$((TOTAL+1))
    if [ "$called" = "$wanted" ]; then RIGHT=$((RIGHT+1)); mark=ok; else mark=WRONG; fi
    printf '  %-5s %6d ms  %-45s %s %s\n' "$mark" "$ms" "$request" "$called" "$args"
    [ -n "$result" ] && printf '                    tool said: %s\n' "${result:0:100}"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$request" "$wanted" "$called" "$ms" "$args" "$result" "$reply" >> "$OUTFILE"
done <<< "$REQUESTS"

jarvis fast on >/dev/null
echo "$NAME: $RIGHT of $TOTAL correct. Details in $OUTFILE"
