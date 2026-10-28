---
name: jarvis-harness
description: How to build, boot, test and inspect JarvisOS on the build server with `jh`, the project harness. Use for any JarvisOS change that has to be compiled, put on the emulator or tested, for "does it work", for build failures, and before any commit or push of frameworks/base, vendor/jarvisos, vendor/llama.cpp or vendor/cactus.
---

# The JarvisOS harness (`jh`)

JarvisOS is an Android ROM (LineageOS 22.2) with an on-device AI service in
`system_server`. Nothing about it can be checked by reading code alone: it has
to be built (minutes to hours), booted on the emulator, and driven. `jh` is the
one way in. Every command ends with `jh: OK ...` or `jh: FAIL ...` and exits
0 or 1, so read the last line first.

Source: `vendor/jarvisos/harness/` (the `jh` script, `hooks/guard.py`, this
skill). Run `jh install` once on a fresh server.

## The loop

```
edit  →  build  →  put on emulator  →  test  →  read the failure  →  edit
```

| Changed | Build | Put on emulator | Time |
|---|---|---|---|
| An app (`apps/Jarvis`, `apps/JarvisTicTacToe`, ...) | `jh build app Jarvis --wait` | `jh deploy Jarvis` | ~1.5 min (15 min the first time after an image build) |
| The service (`frameworks/base/services/jarvis`), JNI, sepolicy, `jarvisos.mk` | `jh build image --wait` | `jh emu restart` | ~4 min build |
| `core/java` API or `core/res` manifest | `jh build image --wait` | `jh emu restart --wipe`, then `jh models` | ~45 min, wipes /data |
| Only want to know it compiles | `jh build check --wait` | — | a few min |

`jh cycle app Jarvis jarvis-app` and `jh cycle image fast` do a whole loop and
stop at the first step that fails.

Builds run in tmux `build`, the emulator in tmux `emu`. A build of more than a
few minutes: start it without `--wait`, do something else, then `jh build wait`.
`jh build rom` makes the Pong (phone) ROM; switching between the emulator and
Pong re-runs Soong analysis each time, so don't alternate casually.

## Tests

`jh test list` names the suites. Groups:

- `jh test fast`: host + history + jarvis-app + tictactoe-quick. Before a commit.
- `jh test all`: everything, full tic-tac-toe (~25 min). Before a push.
- `host` and `history` need no emulator; the rest do and queue on a lock, so
  two device suites never run at once.

A failing suite prints its FAIL lines and Jarvis's warnings from logcat during
the run. Full output and a JSON result per run are in `/srv/jarvis/state/runs/`.

## Looking inside

- `jh status`: repos (pushed or not), tmux, build, emulator, models, disk. Start here.
- `jh logs [MIN]`: Jarvis logcat lines, crashes, SELinux denials.
- `jh q "turn on the flashlight"`: ask Jarvis, timed.
- `jh trace`: the last session turn (model calls, tool calls, times).
- `adb shell cmd jarvis ...` for everything else (`status`, `tools`, `tool NAME 'JSON'`,
  `engine`, `history`, `dream`, `session`). The shell cannot use the Binder methods
  directly; that is deliberate (signature permission).

## A shared server

Kevin, Sam and Desmond (group `jarvis`) share one tree, one `out/` and one
emulator. Everything shared lives in `/srv/jarvis`: `state/` (locks, the
watching flag, test runs), `logs/` (build and emulator logs and summaries),
`models/` (read-only test models), `ccache/`.

- One build at a time: `jh build` refuses while anyone's build holds the lock
  and says whose it is; `jh build wait` waits for it, whoever started it.
- One emulator: `jh emu up` uses the one that is running or starting. Before
  `jh emu restart`, `down` or `--wipe`, check `jh status`: someone may be using it.
- `jh status` checks GitHub over https, so it works without a GitHub key.

## Reading lists

`jh read` shows them, `jh read NAME` lists one, `jh read NAME N` opens file N.
When you build a new app or change a feature across files, add or update one
in `harness/reading/` (rules in `harness/CLAUDE.md`) and run `jh read --check`.

## Rules (enforced by the hook, so a denial is not a bug)

- Kevin decides every `git push`. Ask in chat.
- `breakfast sdk_phone_x86_64 userdebug`, never `lunch`.
- Never `pkill qemu`; use `jh emu down`.
- Never touch tmux session `jarvis` (Kevin's Claude).
- ROM zips are backed up with `cp`, never `ln`; Kevin decides what is deleted.
- `jh watch on` means Kevin is watching the emulator through scrcpy: no taps,
  installs, restarts or device tests until he runs `jh watch off`.

## Saying what was proven

Say which layer a claim rests on: host test, emulator, or phone (none yet).
"Compiles", "boots", "the test passed" and "works on a phone" are different
claims. Voice tests use synthetic WAV clips, game tests use synthetic taps;
say so. When a build breaks or something that looked done turns out not to
work, add an entry to the talk's roadblock log (the `jarvis-presentation` skill).
