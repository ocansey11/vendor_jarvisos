# Testing JarvisOS

JarvisOS is tested in four layers. Each one proves something the layer before
it cannot, and none of them replaces the next. When reporting progress, say
which layer a claim rests on.

| # | Layer | Where it runs | What it proves | What it cannot prove |
|---|-------|---------------|----------------|----------------------|
| 1 | ObjectBox store test | build server (JVM + Linux ObjectBox library) | The generated database code opens a real store; every store call the service makes works; data survives close and reopen | Anything about Android, `system_server` or SELinux |
| 2 | Unit tests | build server (JVM, Android classes stubbed) | Agent routing, tool-call parsing and memory-consolidation logic | Anything that touches the store, Cactus or the framework |
| 3 | Emulator boot | Android emulator on the build server (`lineage_sdk_phone_x86_64`) | The ROM boots with the service in it; `system_server` survives; the binder services register; the store opens under SELinux; tool definitions load | Inference (Cactus is arm64-only and is absent on x86_64); anything hardware-specific |
| 4 | Phone | Nothing Phone 2 (`Pong`) | Everything, including inference with real models | — |

## The harness: `jh`

`vendor/jarvisos/harness/jh` wraps everything below into one command
(`jh install` puts it on PATH and wires the Claude Code hooks, skills and CLAUDE.md):
`jh status`, `jh build image|check|app NAME|rom`, `jh emu up|down|restart`,
`jh test fast|all|SUITE`, `jh cycle`, `jh logs`, `jh q`, `jh trace`,
`jh watch on|off`, `jh read`. Results of every run are kept in
`/srv/jarvis/state/runs/`, build and emulator logs in `/srv/jarvis/logs/`; the
server is shared, so builds take a lock and everyone uses one emulator.
`harness/skill/SKILL.md` explains the loop; `harness/hooks/guard.py` holds the
rules agents may not break (pushes, `lunch`, `pkill qemu`, taps while Kevin is
watching). The scripts below still work on their own.

## Running the host tests (layers 1 and 2)

```bash
vendor/jarvisos/tests/run-all.sh
```

Prints one line per test and exits non-zero if anything failed. Takes about
ten seconds once Gradle has downloaded its dependencies.

The two parts can also be run on their own:

```bash
frameworks/base/services/jarvis/objectbox/test.sh        # layer 1
frameworks/base/services/jarvis/objectbox/history-test.sh   # layer 1: History (44 checks)
cd vendor/jarvisos/tests && JAVA_HOME=../../../prebuilts/jdk/jdk21/linux-x86 ./gradlew test   # layer 2
```

### Layer 1: ObjectBox store test

Source: `frameworks/base/services/jarvis/objectbox/StoreSmokeTest.java`.

It compiles the entity classes and the generated ObjectBox code on the host,
opens a store in a temporary directory using the Linux build of the ObjectBox
native library, and runs 36 checks in five groups:

- **Round trip** — put, get and getAll for each of the 12 entities.
- **Tools** — what `ToolScannerService`, `ToolDispatcher` and `JarvisService`
  do: look up an app by package, a tool by name and receiver, a tool by
  `cactusIndexId`; follow `tool.app`; iterate and prune `app.tools`.
- **Indexing** — what `JarvisIndexWorker` and `RetrieveNode` do: find a file
  by path, attach chunks, follow `chunk.sourceFile` and `file.chunks`, remove
  chunks.
- **Sessions** — what the agent nodes and `DreamWorker` do: add turns to a
  session, find finished sessions that are not yet consolidated, mark them.
- **Metadata search** — the `contains`, `in` and `equal` queries
  `MetadataSearch` runs.

It then closes the store and reopens it, which fails if the generated model
does not match what was written to disk.

After changing an `@Entity` class, run
`frameworks/base/services/jarvis/objectbox/regenerate.sh` first, then this
test, and commit the regenerated files with `objectbox/default.json`.

### Layer 2: unit tests

Source: `vendor/jarvisos/tests/src/test/java`. Android classes are replaced by
the minimal stand-ins in `tests/stubs`.

- **RouterNodeTest** (12) — which node the agent loop goes to next for a given
  model output and session state.
- **ToolNodeParsingTest** (8) — extracting a tool call from model output,
  including malformed output, and recording the result on the session.
- **DreamWorkerTest** (9) — building a session summary and merging extracted
  facts into the user context.
- **CompletionTest** (11), **FlatVectorIndexTest** (10), **NeedleTest** (4),
  **PcmTest** (5) — engine replies, the vector index, Needle, audio.
- Sessions (Agent Experience, 17 Oct 2026):
  **SessionManifestTest** (6) — the sample game's real manifest parses;
  broken manifests are refused whole. **SessionToolsTest** (8) — the tool
  fence inside and outside a session, `say` length and emoji-safe cutting,
  the event size and rate limits. **SessionTurnTest** (9) — a scripted
  model against a fake game: what the model is offered (only the open tools,
  the legal cells as an enum, the grammar options), retries after an
  illegal move or a closed tool, giving up, one `say` a turn, the line after
  a silent move, the prompt's parts and its size budget.
  **BoardTest** (6) — the sample game's rules and `evaluate_moves`.

## Emulator boot (layer 3)

```bash
tmux new -d -s build vendor/jarvisos/tests/emulator/build.sh   # full image, 1-3 h cold
tmux new -d -s emu   vendor/jarvisos/tests/emulator/run.sh     # headless boot
vendor/jarvisos/tests/emulator/check.sh                        # once it has booted
m JarvisAlarmDemo && vendor/jarvisos/tests/emulator/app-tools.sh   # app-tool path, 22 checks
vendor/jarvisos/tests/emulator/jarvis-app.sh                   # the Jarvis app, 19 checks
vendor/jarvisos/tests/emulator/tictactoe.sh [--quick]          # sessions: the game vs Jarvis, ~15 min (24 checks with --quick)
```

`check.sh` prints, in order: whether boot completed; whether `system_server`
is alive; whether the `jarvis` and `jarvis_tools` services are registered; the
replies to `isReady`, a query and the tool list (through `cmd jarvis`, since
the shell is refused on the Binder methods themselves); the files
under `/data/system/jarvis`; the Jarvis lines from logcat; any crash; and any
SELinux denial involving `system_server`.

`app-tools.sh` installs the sample app in `apps/JarvisAlarmDemo` (a toy alarm
list with `demo_list_alarms`, `demo_set_alarm` and `demo_delete_alarm`) and
calls its tools through `adb shell cmd jarvis tool NAME 'JSON'`, which
bypasses the model. It is also the template for a Jarvis-aware app: one
exported receiver per tool, a read tool for current state, and write tools
that report "already exists" instead of repeating the action.

`jarvis-app.sh` drives the built-in Jarvis app (`apps/Jarvis`) by reading the
screen and tapping: the app holds `ACCESS_JARVIS_SERVICE` and the shell is
refused without it; the status line matches `cmd jarvis status`; a tool run
from the Tools tab shows its reply; a message typed in Chat shows the
service's own reply. To try a change to the app without an image build:
`m Jarvis && adb install -r $ANDROID_PRODUCT_OUT/system/app/Jarvis/Jarvis.apk`.

`history-test.sh` (17 Oct 2026) runs the service's `history/History.java`
against a real ObjectBox store on the host, with stubs for `android.util.Log`
and `android.os.SystemProperties` and the org.json jar from the Gradle cache:
a chat is kept with its tool calls, a request within 30 minutes continues the
conversation, a game session keeps its events, Jarvis's calls and lines in
order, DreamWorker gets one input per record, and anything older than
`persist.sys.jarvis.history_hours` (default 24) is forgotten with its
messages. 44 checks.

`tictactoe.sh` tests sessions (`AGENT_EXPERIENCE.md` section 11) with the
sample game `apps/JarvisTicTacToe`, which is part of the image: the session
manifest is read at boot; outside a session `move` is refused and not
offered to Needle; the game opens with its new-game pop-up and works without
Jarvis (a friend game opens no session; pause and resume); "Play with
Jarvis" in the pop-up opens a session; pause ends it and resume opens a new
one; during it
`set_alarm` and `open_app` are refused and `say` reaches the game's speech
bubble; Home and killing the app end it. Without `--quick` it then plays
three whole games with uiautomator taps (the player: win, else block, else
centre, corners, edges): `evaluate_moves` on, off, and a game started with
`cmd jarvis query "let's play tic-tac-toe"`. After the first game it checks
the game is in `cmd jarvis history`, then a rematch, where the marks swap
and Jarvis, now X, must make the first move. Each Jarvis turn's trace
(model calls, tool calls, times, prompt tokens reused) is printed and kept in
`~/tictactoe-*.log`; `cmd jarvis session` shows the last one. It needs the
chat model; Needle is used for the typed start request. The taps are
synthetic: no person has played it yet.

To watch a game from a PC, see the scrcpy note in the test-setup memory
(`ssh -N -L 5555:localhost:5555 root@<server>` then `scrcpy`), open
"Tic-tac-toe" on the emulator and touch Play with Jarvis.

**A model on the emulator.** llama.cpp (`vendor/llama.cpp`) is the engine
there; Cactus is arm64-only. The emulator must be started by `run.sh`, which
passes `-qemu -cpu host` (the engine needs AVX2 and refuses to load without
it). Install models through the service, since nothing else may write to its
folder:

```bash
adb shell -T cmd jarvis install-model embed < all-MiniLM-L6-v2-Q8_0.gguf
adb shell -T cmd jarvis install-model chat  < Qwen3-1.7B-Q8_0.gguf
adb shell cmd jarvis engine          # which engine, and where it looks for models
adb shell "cmd jarvis query 'set an alarm for 7:30 called gym'"
```

**Voice and the fast path.** Needle 3 and Whistle are installed the same way
(the files are in `/srv/jarvis/models/needle`), then `voice.sh` checks that typed
and spoken requests switch the torch without the chat model and that the
camera service confirms it:

```bash
adb shell -T cmd jarvis install-model needle  < needle3.cact
adb shell -T cmd jarvis install-model whistle < whistle.cact
adb shell "cmd jarvis route 'turn on the flashlight'"       # what would run, and how sure
adb shell -T cmd jarvis voice < /srv/jarvis/models/voice-clips/flash-on-alan.wav
vendor/jarvisos/tests/emulator/voice.sh [--slow]            # --slow also times the chat model
```

The emulator runs with no audio, so speech is fed in as WAV files; the clips
are synthetic (piper and espeak-ng). 8 Oct 2026: 29 of 29.

The test models are in `/srv/jarvis/models` on the build server. They survive an
emulator restart; a wiped `/data` needs them installed again.

Expected on the emulator with no model installed: the store opens and tools load, the three models
report "not ready" (no Cactus on x86_64, and no model files), and queries
return an error string rather than an answer.

The emulator needs KVM and, on Ubuntu 22.04, `apt install libnss3 libxi6
libxkbfile1`. Cuttlefish is not used: its host package needs glibc 2.36 or
newer.

## Results

### 2 October 2026 — build server, lineage-22.2

| Layer | Result |
|-------|--------|
| 1. ObjectBox store test | **36 of 36 checks pass** (ObjectBox 4.0.2 native, Linux x86_64) |
| 2. Unit tests | **88 of 88 pass** on 17 Oct 2026 (58 before, plus sessions: SessionManifest 6, SessionTools 8, SessionTurn 9, Board 6, Completion +1) |
| 3. Emulator boot | **Boots; service ready; 22 of 22 app-tool checks pass** — after four fixes, see below. Jarvis app (5 Oct 2026): **19 of 19** (`tests/emulator/jarvis-app.sh`). With llama.cpp and a model installed (5 Oct 2026): plain-English requests call tools and get answers, 10 to 26 s each. With Needle 3 and Whistle (8 Oct 2026): typed and spoken tool requests in about 0.25 s, **29 of 29** (`tests/emulator/voice.sh`). Sessions (17 Oct 2026): **34 of 34** in a full `tictactoe.sh` run (three whole games), then **24 of 24** with `--quick` after a fresh boot; app-tool 22/22, Jarvis app 19/19, voice 29/29 again |
| 4. Phone | no device yet |

Layer 3 in detail (third and fourth boots, 15:52–15:54):

- Android boots; `system_server` stays up; no crashes; no SELinux denials
  involving `system_server` or Jarvis.
- `jarvis` and `jarvis_tools` are registered; `cmd jarvis status` reports
  ready, store open, 1 app, 14 tools.
- After a reboot without wiping data the store reopens with the same 14
  tools (not 28).
- A built-in tool runs: `cmd jarvis tool get_battery_status` → "Battery: 100%
  (charging)".
- A query without a model fails cleanly: "I wasn't able to generate a
  response. Please try again."
- The three models report "not ready" (no Cactus on x86_64, no model files).
- `tests/emulator/app-tools.sh`, 22 checks: the sample app's three tools are
  discovered on install and removed on uninstall; set / set again / list /
  delete behave as designed ("already exists", "Nothing was changed"); bad
  arguments come back with the app's own message; a non-system sender is
  refused.

Not working on the emulator, and not fixed:

- **File watching.** The observers start and report 0 directories:
  `system_server` cannot see shared storage. Automatic indexing needs a
  design change.
- **The scheduled jobs have not fired.** The indexer (15 min) and DreamWorker
  (24 h) log "Scheduled"; the emulator was not left running long enough to
  see either run.
- **Inference**, by construction.

Found and fixed on the way (full detail, with before/after code, in
`skills/jarvis-presentation/references/roadblocks.md`):

- The ObjectBox `*_` classes were hand-written stubs whose cursor factories
  threw, so no record could have been read or written on a device.
- A missing native library during start-up would have killed `system_server`.
- `DreamWorker` dropped the final answer from the summary of a session with
  no turns (caught by `DreamWorkerTest`).
- **Emulator:** the service never started, because a static field called the
  storage API before the storage service exists.
- **Emulator:** every app receiving a tool call crashed on the reply channel.
- **Emulator:** any app could send the tool broadcast; it is now system-only.
- **Emulator:** the dispatcher hid apps' error messages and ran tools with no
  arguments when given invalid JSON.

### 17 October 2026 — sessions (Agent Experience), emulator

What was seen, with Qwen3-1.7B Q8_0 through llama.cpp, the grammar on, and
synthetic taps:

- Jarvis plays whole games: three a run, from the button (twice) and from a
  typed "let's play tic-tac-toe" (Needle chose `start_tic_tac_toe`; the reply
  "... The Tic-tac-toe session with Jarvis is open." came in about 1 s).
- Every move Jarvis made was legal; no turn gave up in the last three runs.
- Turn times: 11 to 22 s with `evaluate_moves` (two model calls, the second
  about 3.5 s thanks to prompt reuse); 24 to 31 s when a closing say-only
  call is added (it is, when Jarvis has not spoken; about 8 to 9.5 s);
  13 to 17 s with `evaluate_moves` off. A game's first call reads the whole
  prompt cold: 13 to 19 s.
- `evaluate_moves` on: draws (after the tool also named the best cells);
  off: a loss. One game per setting really, since play is deterministic.
- Found on the way: tool apps in the ROM were never scanned at boot
  (roadblock 36), the grammar let the model talk before calling (37), the
  model misread a correct tool (38), half an emoji hung the test (39).

