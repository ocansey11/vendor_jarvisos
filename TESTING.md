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

## Running the host tests (layers 1 and 2)

```bash
vendor/jarvisos/tests/run-all.sh
```

Prints one line per test and exits non-zero if anything failed. Takes about
ten seconds once Gradle has downloaded its dependencies.

The two parts can also be run on their own:

```bash
frameworks/base/services/jarvis/objectbox/test.sh        # layer 1
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

## Emulator boot (layer 3)

```bash
tmux new -d -s build vendor/jarvisos/tests/emulator/build.sh   # full image, 1-3 h cold
tmux new -d -s emu   vendor/jarvisos/tests/emulator/run.sh     # headless boot
vendor/jarvisos/tests/emulator/check.sh                        # once it has booted
```

`check.sh` prints, in order: whether boot completed; whether `system_server`
is alive; whether the `jarvis` and `jarvis_tools` services are registered; the
replies to `isReady`, `processQuery`, `isIndexed` and `listTools`; the files
under `/data/system/jarvis`; the Jarvis lines from logcat; any crash; and any
SELinux denial involving `system_server`.

Expected on the emulator: the store opens and tools load, the three models
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
| 2. Unit tests | **29 of 29 pass** (DreamWorker 9, RouterNode 12, ToolNodeParsing 8) |
| 3. Emulator boot | image build in progress; not yet booted |
| 4. Phone | no device yet |

Found and fixed while getting these to run:

- The ObjectBox `*_` classes were hand-written stubs whose cursor factories
  threw, so no record could have been read or written on a device. Replaced
  with real generated code.
- A missing native library during service start-up would have raised an
  `Error` past a `catch (Exception)` and killed `system_server`.
- `DreamWorker` dropped the final answer from the summary of a session with
  no turns (caught by `DreamWorkerTest`).
