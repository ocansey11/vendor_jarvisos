#!/usr/bin/env python3
"""PreToolUse hook for Bash: the JarvisOS rules that were learned the hard way,
enforced instead of remembered. Each rule names the incident behind it.

Denied commands get a reason that says what to do instead; `git push` and
deleting a ROM zip are not denied but put to Kevin as a question.
"""
import json
import os
import re
import sys

WATCHING = os.path.expanduser("~/.jh/watching")

# Where a command starts: the line start, or after ; & | && || ( or "then"/"do".
# Rules anchor on it so that a word inside text (a heredoc, a commit message,
# a note about `lunch`) is not mistaken for running it.
CMD = r"(?:^|[;&|(\n]\s*|\b(?:then|do|sudo|nice(?:\s+-n\s*-?\d+)?|time)\s+)"

# (pattern, decision, reason)
RULES = [
    (CMD + r"lunch\b",
     "deny",
     "Use `breakfast sdk_phone_x86_64 userdebug` (or `jh build`), never `lunch`: "
     "on 17 Oct 2026 `lunch lineage_sdk_phone_x86_64-...` turned a 4-minute build into a full 62k-step one."),
    (CMD + r"(pkill|killall)\b[^;&|\n]*(qemu|emulator)",
     "deny",
     "Stop the emulator with `jh emu down` (adb emu kill). `pkill -f qemu` matches the calling shell and kills it."),
    (CMD + r"tmux\b[^;&|\n]*(kill-session|new(-session)?)\b[^;&|\n]*-[st]\s*jarvis\b|" + CMD + r"tmux\s+kill-server\b",
     "deny",
     "tmux session `jarvis` is where Kevin runs Claude; leave it alone. Builds go in `build`, the emulator in `emu`."),
    (CMD + r"ln\b[^;&|\n]*(artifacts|lineage-[^ ]*\.zip)",
     "deny",
     "Back up ROM zips with `cp`, never `ln`: the 20 Sep vanilla backup was a hard link and the next build overwrote it."),
    (CMD + r"rm\b[^;&|\n]*(~|/root)/artifacts",
     "ask",
     "This deletes something in ~/artifacts (ROM zips). Kevin decides which zips go."),
    (CMD + r"git\b[^;&|\n]*\spush\b",
     "ask",
     "Kevin decides every push. Ask him in chat before pushing."),
]

# Builds take minutes to hours; in the foreground they hit the tool timeout and
# die with the shell. They belong in tmux 'build' (jh build does that).
FOREGROUND_BUILD = re.compile(r"(^|[;&|]\s*|\bthen\s+|&&\s*)(m|mka|mm|mmm|make)\s+[A-Za-z-]")

# What touches a screen Kevin is watching through scrcpy.
WATCHED_ACTIONS = re.compile(
    r"\binput\s+(tap|swipe|text|keyevent)\b|\buiautomator\b|\bam\s+(start|force-stop)\b"
    r"|tests/emulator/(app-tools|jarvis-app|voice|tictactoe)\.sh|\badb\b[^;&|]*\bemu\s+kill\b"
    r"|\bjh\s+(emu\s+(down|restart)|deploy|cycle)\b|\badb\b[^;&|]*\binstall\b")


def decide(cmd):
    for pattern, decision, reason in RULES:
        if re.search(pattern, cmd):
            return decision, reason
    if FOREGROUND_BUILD.search(cmd) and "tmux" not in cmd and "jh build" not in cmd:
        return "deny", ("Run builds in tmux, not in the foreground: `jh build image|check|app NAME|-- TARGETS` "
                        "(tmux session `build`), then `jh build wait`.")
    if os.path.exists(WATCHING) and WATCHED_ACTIONS.search(cmd):
        since = open(WATCHING).read().strip()
        return "deny", (f"Kevin is watching the emulator on scrcpy (since {since}). Taps, installs and restarts "
                        "would look like the app acting on its own (17 Oct 2026). Ask him first; he ends it "
                        "with `jh watch off`.")
    return None, None


def main():
    try:
        event = json.load(sys.stdin)
    except ValueError:
        return
    cmd = (event.get("tool_input") or {}).get("command") or ""
    decision, reason = decide(cmd)
    if decision:
        json.dump({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": decision,
            "permissionDecisionReason": reason,
        }}, sys.stdout)


if __name__ == "__main__":
    main()
