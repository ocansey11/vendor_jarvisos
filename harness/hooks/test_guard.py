#!/usr/bin/env python3
"""Checks guard.py's decisions on commands it must block, ask about, or allow.
   python3 vendor/jarvisos/harness/hooks/test_guard.py
"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import guard  # noqa: E402

CASES = [
    # (command, decision without watching, decision while Kevin watches)
    ("lunch lineage_sdk_phone_x86_64-ap4a-userdebug", "deny", "deny"),
    ("source build/envsetup.sh && lunch foo", "deny", "deny"),
    ("pkill -f qemu-system", "deny", "deny"),
    ("tmux kill-session -t jarvis", "deny", "deny"),
    ("tmux kill-session -t emu", None, None),
    ("ln ~/out/x.zip ~/artifacts/", "deny", "deny"),
    ("rm ~/artifacts/lineage-22.2-20261002-JARVIS-Pong-r2.zip", "ask", "ask"),
    ("git -C frameworks/base push github jarvis-22.2", "ask", "ask"),
    ("source build/envsetup.sh && breakfast sdk_phone_x86_64 && m services.jarvis", "deny", "deny"),
    ("tmux new -d -s build 'm droid'", None, None),
    ("jh build image --wait", None, None),
    ("git log --oneline -3", None, None),
    ("grep -n 'm Jarvis' TESTING.md", None, None),
    ("adb shell input tap 100 200", None, "deny"),
    ("jh emu restart", None, "deny"),
    ("jh status", None, None),
    # Words inside text are not commands (22 Oct 2026: notes for the talk that
    # quoted the lunch rule were refused).
    ("python3 - <<'PY'\ns = 'never use `lunch lineage_sdk`'\nPY", None, None),
    ("git commit -m 'Explain why pkill -f qemu is blocked'", None, None),
    ("echo 'ask Kevin before git push'", None, None),
]


def main():
    failed = 0
    watching_dir = tempfile.mkdtemp()
    for watching in (False, True):
        guard.WATCHING = os.path.join(watching_dir, "watching")
        if watching:
            open(guard.WATCHING, "w").write("test")
        for cmd, plain, watched in CASES:
            want = watched if watching else plain
            got, _ = guard.decide(cmd)
            ok = got == want
            failed += not ok
            label = cmd.replace("\n", "\\n")[:70]
            print("  %s  %-6s %-72s%s" % ("pass" if ok else "FAIL", str(got),
                  label, "" if ok else "  (wanted %s)" % want) + ("  [watching]" if watching else ""))
    print("%d checks, %d failed" % (2 * len(CASES), failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
