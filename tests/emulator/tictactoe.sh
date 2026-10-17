#!/bin/bash
# End-to-end test of sessions (Agent Experience, AGENT_EXPERIENCE.md section 11)
# with the sample game on a running emulator: the session manifest is read at
# install, the tool fence holds inside and outside a session, and Jarvis
# plays whole games of tic-tac-toe against moves tapped into the game through
# uiautomator (synthetic taps, not a person). Needs the chat model installed.
#   vendor/jarvisos/tests/emulator/tictactoe.sh            (all of it, ~15 min)
#   vendor/jarvisos/tests/emulator/tictactoe.sh --quick    (no full games)
# Each turn's trace (model calls, tool calls, times) goes to ~/tictactoe-*.log.
TOP="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
ADB="$TOP/out/host/linux-x86/bin/adb"
PKG=com.jarvisos.tictactoe
LOG=~/tictactoe-$(date +%Y%m%d-%H%M).log
QUICK=0; [ "$1" = "--quick" ] && QUICK=1
PASS=0; FAIL=0

expect() {   # expect "what" "substring" "actual output"
    if [[ "$3" == *"$2"* ]]; then PASS=$((PASS+1)); echo "  pass  $1"
    else FAIL=$((FAIL+1)); echo "  FAIL  $1"; echo "        wanted: $2"; echo "        got:    ${3:0:400}"; fi
}
refuse() {   # refuse "what" "substring that must be absent" "actual output"
    if [[ "$3" != *"$2"* ]]; then PASS=$((PASS+1)); echo "  pass  $1"
    else FAIL=$((FAIL+1)); echo "  FAIL  $1"; echo "        must not contain: $2"; echo "        got: ${3:0:400}"; fi
}
tool() { "$ADB" shell "cmd jarvis tool $1 '${2:-}'" 2>&1 | tr -d '\r'; }
session() { "$ADB" shell cmd jarvis session 2>&1 | tr -d '\r'; }
# uiautomator cannot dump while the "thinking" spinner animates, and then
# leaves the last file behind; delete it first and try again until it works.
screen() {
    local xml
    for i in 1 2 3 4 5; do
        "$ADB" shell "rm -f /sdcard/ttt-ui.xml; uiautomator dump /sdcard/ttt-ui.xml" >/dev/null 2>&1
        xml="$("$ADB" shell cat /sdcard/ttt-ui.xml 2>/dev/null | tr -d '\r')"
        [[ "$xml" == *"<hierarchy"* ]] && { echo "$xml"; return; }
        sleep 1
    done
}
# Centre of the first node whose text or content-desc is exactly $1.
centre() {
    python3 -c '
import re, sys
xml, want = sys.stdin.read(), sys.argv[1]
for node in re.findall(r"<node [^>]*>", xml):
    text = re.search(r" text=\"([^\"]*)\"", node).group(1)
    desc = re.search(r"content-desc=\"([^\"]*)\"", node).group(1)
    if want in (text, desc):
        a, b, c, d = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node).groups())
        print((a + c) // 2, (b + d) // 2); break' "$1"
}
tap() {   # waits for it: in the first minutes after boot the screen is often not readable
    local xy
    for i in $(seq 1 15); do
        xy="$(screen | centre "$1")"; [ -n "$xy" ] && break; sleep 2
    done
    [ "$i" -gt 1 ] && echo "        (waited for '$1': $i tries)"
    [ -n "$xy" ] || { echo "        (nothing on screen called '$1')"; return 1; }
    "$ADB" shell input tap $xy; sleep 1
}
# The board as 9 characters, read off the cell buttons.
board() {
    python3 -c '
import re, sys
cells = ["."] * 9
for node in re.findall(r"<node [^>]*>", sys.stdin.read()):
    m = re.search(r"content-desc=\"cell (\d)\"", node)
    if m:
        t = re.search(r" text=\"([^\"]*)\"", node).group(1)
        cells[int(m.group(1))] = t if t in ("X", "O") else "."
print("".join(cells))'
}
# The player's move: win, else block, else centre, corners, edges. A fair
# opponent, so a Jarvis that plays badly loses.
pick() {
    python3 -c '
import sys
b = sys.argv[1]
lines = [(0,1,2),(3,4,5),(6,7,8),(0,3,6),(1,4,7),(2,5,8),(0,4,8),(2,4,6)]
def finishing(mark):
    for l in lines:
        marks = [b[i] for i in l]
        if marks.count(mark) == 2 and marks.count(".") == 1: return l[marks.index(".")]
for c in (finishing("X"), finishing("O")):
    if c is not None: print(c); sys.exit()
for c in (4, 0, 2, 6, 8, 1, 3, 5, 7):
    if b[c] == ".": print(c); sys.exit()' "$1"
}
field() {   # field ID: the text of the view with that resource id
    python3 -c '
import re, sys
for node in re.findall(r"<node [^>]*>", sys.stdin.read()):
    if "id/" + sys.argv[1] + "\"" in node:
        print(re.search(r" text=\"([^\"]*)\"", node).group(1)); break' "$1"
}
open_game() {   # open_game [true|false]: a fresh game screen, evaluate_moves switch set
    "$ADB" shell am force-stop $PKG
    "$ADB" shell input keyevent KEYCODE_WAKEUP
    "$ADB" shell am start -W -n $PKG/.GameActivity --ez evaluate_moves "${1:-true}" >/dev/null 2>&1
    sleep 2
}

# Plays one whole game: the player's taps against Jarvis's turns. Prints a
# line per move and the result; sets GAME_RESULT, TURNS, GAVE_UP, SAID, TURN_MS.
play_game() {
    GAME_RESULT=""; TURNS=0; GAVE_UP=0; SAID=0; TURN_MS=""
    local ui b cell st waited trace s
    # The previous game's last turn is still shown; only a new one counts.
    local last_turn; last_turn="$(session | sed -n 's/^last turn: //p')"
    for move in 1 2 3 4 5; do
        ui="$(screen)"; b="$(echo "$ui" | board)"; st="$(echo "$ui" | field status)"
        case "$st" in *win*|*Draw*|*left*|*stopped*) break;; esac
        cell="$(pick "$b")"
        [ -n "$cell" ] || break
        "$ADB" shell input tap $(echo "$ui" | centre "cell $cell")
        echo "        player  -> $cell   ($b)"
        # Wait for Jarvis through the service, not the screen (uiautomator
        # cannot read it while the spinner turns): until a new turn trace
        # appears and no turn runs, or the session is gone (game over).
        waited=0
        while [ $waited -lt 180 ]; do
            sleep 3; waited=$((waited+3))
            s="$(session)"
            [[ "$s" == *"in a turn"* ]] && continue
            [[ "$s" == *"open: none"* ]] && break
            [ "$(echo "$s" | sed -n 's/^last turn: //p')" != "$last_turn" ] && break
        done
        sleep 1
        ui="$(screen)"; st="$(echo "$ui" | field status)"
        trace="$(session | sed -n 's/^last turn: //p')"
        if [ -n "$trace" ] && [ "$trace" != "$last_turn" ]; then
            last_turn="$trace"; TURNS=$((TURNS+1))
            echo "$trace" >> "$LOG"
            python3 -c '
import json, sys
t = json.loads(sys.argv[1])
calls = [c["name"] + json.dumps(c.get("args", {}), separators=(",", ":")) for s in t.get("steps", []) for c in s.get("calls", [])]
model = [s.get("model_ms", 0) for s in t.get("steps", [])]
print("        jarvis  %s  %s  %d ms (model %s)" % ("done" if t.get("done") else "GAVE UP", " ".join(calls), t.get("ms", 0), "+".join(map(str, model))))' "$trace"
            TURN_MS="$TURN_MS $(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("ms",0))' "$trace")"
            [[ "$trace" == *'"done":false'* ]] && GAVE_UP=$((GAVE_UP+1))
            [[ "$trace" == *'"name":"say"'* || "$trace" == *'"said_text":"Said'* ]] && SAID=$((SAID+1))
        fi
        bubble="$(echo "$ui" | field bubble)"
        [ -n "$bubble" ] && echo "        bubble: $bubble"
        case "$st" in *win*|*Draw*|*left*) break;; esac
    done
    ui="$(screen)"
    GAME_RESULT="$(echo "$ui" | field status)"
    echo "        final   $(echo "$ui" | board)  $GAME_RESULT"
}

echo "log: $LOG"
"$ADB" shell cmd jarvis status | grep -q "model primary: ready" \
    || echo "  note  no chat model: games will fall back to random moves"

echo "Declared at install"
S="$(session)"
expect "the session manifest was read"     "declared: $PKG/tic_tac_toe" "$S"
expect "with its turn and start tool"      "turn your_turn -> move, start start_tic_tac_toe" "$S"
T="$("$ADB" shell cmd jarvis tools | tr -d '\r')"
expect "start tool registered"             "start_tic_tac_toe  [$PKG/" "$T"
expect "session tools registered"          "evaluate_moves  [$PKG/" "$T"
expect "the game runs as an ordinary app"  "untrusted_app" "$(open_game; "$ADB" shell ps -A -Z | grep $PKG)"

echo "Fence, no session open"
"$ADB" shell cmd jarvis session end >/dev/null
expect "a session tool is refused"         "Error: refused. 'move' is open only during" "$(tool move '{"cell":0}')"
expect "say is no tool outside a session"  "not registered" "$(tool say '{"text":"hi"}')"
refuse "ordinary tools still run"          "refused" "$(tool get_battery_status)"
expect "a model is not offered move"       "calls:" "$("$ADB" shell "cmd jarvis route 'play cell 4'" | tr -d '\r')"
refuse "(Needle's choice is not move)"     "move{" "$("$ADB" shell "cmd jarvis route 'play cell 4'" | tr -d '\r' | tr -d ' ')"

echo "Start from the app (Play with Jarvis)"
open_game true
tap "Play with Jarvis"
S="$(session)"
expect "the button opened the session"     "open: $PKG/tic_tac_toe" "$S"
expect "with every session tool open"      "open tools: say, get_state, evaluate_moves, move" "$S"

echo "Fence, session open"
# Without an open session these calls would really run (an alarm, an app).
if [[ "$S" != *"open: $PKG/tic_tac_toe"* ]]; then
    echo "  FAIL  no session open: stopping before tools with side effects"; exit 1
fi
expect "another app's tool is refused"     "Error: refused. During the Tic-tac-toe session" "$(tool set_alarm '{"hour":7,"minute":0}')"
expect "the fence names what is open"      "say, get_state, evaluate_moves, move" "$(tool open_app '{"name":"Clock"}')"
expect "a session tool runs"               '"board":".........","you":"O"' "$(tool get_state)"
expect "say reaches the app"               "Said to the user: Hello from the test" "$(tool say '{"text":"Hello from the test"}')"
sleep 1
expect "and shows as the speech bubble"    "Hello from the test" "$(screen | field bubble)"
expect "a move out of turn is refused by the game" "Error: it is not your turn" "$(tool move '{"cell":0}')"

echo "Ending"
"$ADB" shell input keyevent KEYCODE_HOME; sleep 2
S="$(session)"
expect "going home ends the session"       "open: none" "$S"
# The game ends it itself in onStop; Jarvis's own foreground check may get there first.
[[ "$S" == *"last ended: tic_tac_toe: app went to the background"* || "$S" == *"last ended: tic_tac_toe: ended by the app"* ]] \
    && { PASS=$((PASS+1)); echo "  pass  by the app or by Jarvis: $(echo "$S" | sed -n 's/^last ended: //p')"; } \
    || { FAIL=$((FAIL+1)); echo "  FAIL  unexpected end: $S"; }
expect "and the tools close again"         "Error: refused. 'get_state' is open only" "$(tool get_state)"
# Jarvis must not rely on the app: kill it mid-session, with no onStop.
open_game true
tap "Play with Jarvis"
expect "a new session is open"             "open: $PKG/tic_tac_toe" "$(session)"
"$ADB" shell am force-stop $PKG; sleep 2
S="$(session)"
expect "killing the app ends it"           "open: none" "$S"
[[ "$S" == *"last ended: tic_tac_toe: app died"* || "$S" == *"last ended: tic_tac_toe: app went to the background"* ]] \
    && { PASS=$((PASS+1)); echo "  pass  ended by Jarvis, not the app: $(echo "$S" | sed -n 's/^last ended: //p')"; } \
    || { FAIL=$((FAIL+1)); echo "  FAIL  unexpected end: $S"; }

if [ $QUICK = 1 ]; then
    echo; echo "$PASS passed, $FAIL failed (quick: no games)"; exit $((FAIL > 0))
fi

echo "Game 1: started by the button, evaluate_moves on"
open_game true
tap "Play with Jarvis"
play_game
[[ "$GAME_RESULT" == *win* || "$GAME_RESULT" == *Draw* ]] && PASS=$((PASS+1)) && echo "  pass  ended in a result: $GAME_RESULT" \
    || { FAIL=$((FAIL+1)); echo "  FAIL  no result: $GAME_RESULT"; }
[ $TURNS -ge 2 ] && PASS=$((PASS+1)) && echo "  pass  Jarvis took $TURNS turns, gave up $GAVE_UP times, said something in $SAID; ms:$TURN_MS" \
    || { FAIL=$((FAIL+1)); echo "  FAIL  Jarvis took only $TURNS turns"; }
expect "used evaluate_moves"               '"name":"evaluate_moves"' "$(cat "$LOG")"
expect "the session ended with the game"   "game_over" "$(session)"
G1_SAID=$SAID

echo "Game 2: started by the button, evaluate_moves off"
open_game false
tap "Play with Jarvis"
expect "the switch closed evaluate_moves"  "open tools: say, get_state, move" "$(session)"
expect "a closed tool is refused"          "Error: refused" "$(tool evaluate_moves)"
MARK=$(wc -l < "$LOG")
play_game
[[ "$GAME_RESULT" == *win* || "$GAME_RESULT" == *Draw* ]] && PASS=$((PASS+1)) && echo "  pass  ended in a result: $GAME_RESULT" \
    || { FAIL=$((FAIL+1)); echo "  FAIL  no result: $GAME_RESULT"; }
refuse "evaluate_moves never ran"          '"name":"evaluate_moves","args":{},"result":"{' "$(tail -n +$((MARK+1)) "$LOG")"
G2_SAID=$SAID

echo "Game 3: started from Jarvis (\"let's play tic-tac-toe\")"
"$ADB" shell am force-stop $PKG
"$ADB" shell input keyevent KEYCODE_HOME; sleep 1
START=$(date +%s)
REPLY="$("$ADB" shell "cmd jarvis query \"let's play tic-tac-toe\"" | tr -d '\r')"
echo "        reply after $(( $(date +%s) - START )) s: ${REPLY:0:200}"
expect "the start tool ran"                "start_tic_tac_toe" "$(session; "$ADB" logcat -d -s ToolDispatcher | tail -20)"
expect "the game is on screen"             "Tic-tac-toe vs Jarvis" "$(screen)"
expect "and opened the session"            "open: $PKG/tic_tac_toe" "$(session)"
play_game
[[ "$GAME_RESULT" == *win* || "$GAME_RESULT" == *Draw* ]] && PASS=$((PASS+1)) && echo "  pass  ended in a result: $GAME_RESULT" \
    || { FAIL=$((FAIL+1)); echo "  FAIL  no result: $GAME_RESULT"; }

[ $((G1_SAID + G2_SAID + SAID)) -ge 1 ] && PASS=$((PASS+1)) && echo "  pass  Jarvis used say in $((G1_SAID + G2_SAID + SAID)) turn(s)" \
    || { FAIL=$((FAIL+1)); echo "  FAIL  Jarvis never used say"; }

echo
echo "$PASS passed, $FAIL failed (traces in $LOG)"
exit $((FAIL > 0))
