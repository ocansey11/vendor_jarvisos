# JarvisOS Architecture — Service Internals
> For Sam and anyone building on top of or inside the Jarvis service layer.
> Assumes you know: Jarvis is a privileged Android system service that runs models on the device.
> Last brought up to date: 28 October 2026 (sessions, history, two engines, the tool fence).

---

## The Two Layers

JarvisOS code lives in two places in `frameworks/base/`:

```
frameworks/base/
│
├── core/java/android/jarvis/          ← PUBLIC API (what apps see)
│   ├── IJarvisService.aidl            ← queries (text, image, audio), status, indexing, history
│   ├── IToolRegistry.aidl             ← tools: listTools, getTool, searchTools, callTool
│   ├── IJarvisSessionService.aidl     ← sessions: startSession, sendEvent, endSession
│   ├── IJarvisSessionCallback.aidl    ← what Jarvis reports back during a session
│   ├── JarvisManager.java             ← convenience wrapper around IJarvisService (like LocationManager)
│   ├── JarvisSession.java             ← what apps use for sessions (never the AIDL directly)
│   └── JarvisException.java           ← checked exception for API errors
│
└── services/jarvis/java/com/android/server/jarvis/   ← SYSTEM SERVICE (privileged, inside system_server)
    └── (everything below)
```

**Rule:** Apps never import from `server/jarvis/`. They bind to the service via `JarvisManager`
which calls through `IJarvisService` / `IToolRegistry` Binder stubs. The service layer never
leaks its internal classes to callers.

---

## System Service Layer — Full Tree

```
com/android/server/jarvis/
│
├── JarvisService.java               ← THE ENTRY POINT — extends SystemService
│                                    registered in SystemServer.java at boot
│                                    publishes three Binder endpoints:
│                                      "jarvis"         → IJarvisService.Stub
│                                      "jarvis_tools"   → IToolRegistry.Stub
│                                      "jarvis_session" → SessionManager
│
├── core/                         ← SHARED INFRASTRUCTURE (used by everything)
│   ├── JarvisStore.java          ← ObjectBox singleton — one store, all entities
│   ├── ModelRegistry.java        ← named (modelHandle, indexHandle) pairs
│   ├── IndexQueue.java           ← BlockingQueue<IndexTask> cap 500, singleton
│   ├── JarvisScheduler.java      ← background jobs (indexing, DreamWorker)
│   ├── JarvisManager.java        ← internal API manager
│   ├── MyObjectBox.java          ← ObjectBox store builder (hand-written stub)
│   └── JarvisException.java
│
├── agent/                        ← THE AGENT LOOP (JarvisExecutor, max 5 turns)
│   ├── JarvisExecutor.java       ← runs the nodes in sequence
│   ├── RouterNode.java           ← deterministic routing between nodes
│   ├── PlanNode / RetrieveNode / ToolNode / RespondNode
│   ├── SubAgentExecutor.java     ← child loop scoped to one sub-task
│   ├── DreamWorker.java          ← consolidates AgentSessions into UserContext facts
│   └── UserContextHelper.java
│
├── inference/                    ← THE ONLY DOOR TO MODELS (see "Inference" below)
│   ├── Inference.java            ← picks the engine; everything else calls this
│   ├── InferenceEngine.java      ← what an engine must provide
│   ├── CactusEngine.java + CactusWrapper.java   ← Cactus v2 (vendor/cactus), JNI
│   ├── LlamaEngine.java          ← llama.cpp (vendor/llama.cpp), JNI
│   ├── Completion.java           ← completion options and results
│   ├── FlatVectorIndex.java      ← vector index for engines without one
│   ├── ModelName.java            ← a model's human name, read from .gguf metadata
│   ├── Needle.java + NeedleWrapper.java   ← fast-path engine (Needle 3, Whistle)
│   └── Pcm.java                  ← audio input
│
├── session/                      ← SESSIONS (Agent Experience, AGENT_EXPERIENCE.md §11)
│   ├── SessionManager.java       ← IJarvisSessionService + the tool fence
│   ├── SessionRegistry.java      ← reads com.jarvisos.session manifests from apps
│   ├── SessionManifest.java      ← one app's declared session: events, tools, rules
│   ├── SessionPrompt.java        ← builds Jarvis's prompt from manifest + events
│   ├── SessionTurn.java          ← one turn: prompt → model → tool calls / say
│   ├── SessionTools.java         ← the built-in say tool, start tools
│   └── EventLimiter.java         ← caps the events an app can send
│
├── history/
│   └── History.java              ← chats and app sessions for the History tab, kept 24 h
│
├── indexing/                     ← PIPELINE: file → chunks → embeddings → ObjectBox
│   ├── JarvisFileObserver.java   ← inotify watcher on Documents/ Downloads/ Pictures/
│   ├── JarvisIndexWorker.java       ← WorkManager job — drains IndexQueue, embeds, persists
│   ├── TextExtractor.java        ← file → raw text (.txt .md .csv .pdf .docx)
│   └── ChunkingStrategy.java     ← text → sentence-boundary chunks with overlap
│
├── search/
│   └── MetadataSearch.java       ← Stage 1 keyword search over ObjectBox (6 passes)
│
├── model/                        ← OBJECTBOX ENTITIES — pure data, no logic
│   ├── SourceFile.java           ← one row per file JarvisOS knows about
│   ├── DocumentChunk.java        ← one row per embedded chunk of a SourceFile
│   ├── Chunk.java
│   ├── Folder.java               ← directory metadata
│   ├── Conversation.java         ← a session with JarvisOS
│   ├── Message.java              ← one message within a Conversation
│   ├── UserContext.java          ← persistent facts about the user (single row)
│   ├── AccessLog.java            ← which files were accessed per conversation
│   ├── TaskMemory.java           ← what approach was used for a past task
│   ├── AgentSession.java         ← one run of the agent loop or app session (DreamWorker input)
│   ├── AgentTurn.java            ← one turn within an AgentSession
│   │
│   └── *_.java, *Cursor.java     ← ObjectBox query helpers and cursors (hand-written)
│
└── tools/                        ← TOOL REGISTRY — Phase 4
    ├── AppRecord.java            ← one ObjectBox row per installed app with tools
    ├── ToolRecord.java           ← one ObjectBox row per tool (FK → AppRecord)
    ├── ToolScannerService.java   ← scans APKs on install, embeds tool descriptions
    ├── ToolDispatcher.java       ← semantic search → tool selection → broadcast → result;
    │                                every tool call passes its Fence
    ├── SystemToolExecutor.java   ← "@system/" tools run in-process (no broadcast)
    ├── FastPath.java             ← Needle answers simple requests without the chat model
    └── *_.java, *Cursor.java
```

---

## How the Subsystems Connect

### Boot sequence (JarvisService.initializeAsync)

```
JarvisService.onStart()
    │
    ├─ publishBinderService("jarvis", mBinder)         registers IJarvisService endpoint
    ├─ publishBinderService("jarvis_tools", ...)       registers IToolRegistry endpoint
    │
    └─ initializeAsync() [background thread]
           │
    ├─ new ToolDispatcher, new SessionManager; dispatcher.setFence(sessions)
    ├─ publishBinderService("jarvis_session", sessions)
    │
           1. JarvisStore.init()                       opens ObjectBox at /data/system/jarvis/objectbox/
           2. ModelRegistry.register("rag",   ...)     Inference.init() → modelHandle
              ModelRegistry.register("tools", ...)     Inference.indexInit() → indexHandle
              ModelRegistry.registerChatModel("primary", ...)   the chat model
           3. JarvisFileObserver.startWatching()       watches Documents/ Downloads/ Pictures/
           4. JarvisIndexWorker.schedule()                WorkManager (15min, charging only)
           5. ToolScannerService.start()               scans installed packages, embeds tools
```

### Query path (what happens when an app calls processQuery)

```
App → JarvisManager.processQuery(query)
        │  (Binder IPC across process boundary)
        ▼
JarvisService.mBinder.processQuery(query)
        │
        ├─ [TOOL PATH]  ToolDispatcher.resolveAndDispatch(query)
        │                   │
        │                   ├─ CactusWrapper.embed(query)       → float[] queryEmbedding
        │                   ├─ CactusWrapper.indexQuery(...)    → top-5 ToolRecord IDs
        │                   ├─ metadata fallback if no vector hits
        │                   ├─ CactusWrapper.complete(toolsJson + query) → selected tool
        │                   ├─ sendBroadcast → app BroadcastReceiver
        │                   └─ ResultReceiver + CountDownLatch (10s timeout) → result string
        │                   returns null if no tool matches
        │
        └─ [RAG PATH]   (if tool path returned null)
                            │
                            ├─ MetadataSearch.search(query)     → Stage 1: ObjectBox keyword
                            ├─ CactusWrapper.embed(query)       → Stage 2: semantic vector
                            ├─ CactusWrapper.indexQuery(...)    → top-k chunk IDs
                            └─ CactusWrapper.complete(context + query) → response string
```

### Fast path (added 8 Oct 2026)

Before the agent loop, `tools/FastPath` asks Needle 3, a 35 MB tool-calling
model, what the request needs. If Needle names tools with confidence of at
least 0.75 (`persist.sys.jarvis.fast_confidence`, in percent), they run
through `ToolDispatcher.dispatchByName` and their results are the reply; no
chat model is called. Otherwise the request goes on to `JarvisExecutor`
exactly as before. A spoken request (`processQueryWithAudio`, 16 kHz mono
16-bit PCM) takes the same route: Whistle, a 17 MB speech model in the same
engine, transcribes it, and only the transcript is used.

```
processQuery / processQueryWithAudio
  └─ FastPath ── Needle (+ Whistle for audio) ── confident? ── yes ─ ToolDispatcher ─ reply
                                                     └─ no ─ JarvisExecutor (agent loop), with the transcript
```

`inference/NeedleWrapper` is the only entry point to the engine
(`libneedle_jarvis.so`, from `vendor/jarvisos/native/needle` and the prebuilt
in `prebuilts/needle`); everything else uses `inference/Needle`, which keeps
all engine calls on one thread. Needle keeps its own tool index in
`/data/system/jarvis/index_tools_needle/`. Models:
`/data/system/jarvis/models/needle3.cact` and `whistle.cact`.
`cmd jarvis fast off` sends everything through the agent loop.

### Indexing pipeline (background, triggered by file changes)

```
New/changed file detected
        │
        JarvisFileObserver (inotify)
        │  puts IndexTask onto
        ▼
IndexQueue (BlockingQueue, cap 500)
        │  drained by
        ▼
JarvisIndexWorker (WorkManager, 15min intervals, charging only)
        │
        ├─ TextExtractor.extract(file)           → raw text string
        ├─ ChunkingStrategy.chunk(text)          → List<String> chunks
        ├─ ModelRegistry.getReady("rag")         → (modelHandle, indexHandle)
        ├─ CactusWrapper.embed(chunk)            → float[] per chunk
        ├─ CactusWrapper.indexAdd(...)           → stores vector in Cactus index
        └─ ObjectBox: persist SourceFile + DocumentChunk rows
```

### Tool registration (background, triggered on app install)

```
App installed / updated
        │
        ToolScannerService (BroadcastReceiver: ACTION_PACKAGE_ADDED/REPLACED)
        │
        ├─ PackageManager: read <meta-data android:name="jarvis.tools"> from APK manifest
        ├─ Parse tool XML → toolName, description, paramsJson, receiverClass
        ├─ Upsert AppRecord + ToolRecord in ObjectBox
        ├─ ModelRegistry.getReady("tools") → (modelHandle, indexHandle)
        └─ CactusWrapper.embed(rawDefinition)  → store in "tools" Cactus index
```

---

## CactusWrapper — The Inference Boundary

Everything Cactus-related goes through one class. No other file in `server/jarvis/`
touches JNI directly.

```
CactusWrapper (static methods only)
│
├─ init(modelPath, contextSize, ...) → long modelHandle
├─ indexInit(indexDir, dim)          → long indexHandle
├─ embed(modelHandle, text)          → float[]
├─ indexAdd(indexHandle, vector, id) → void
├─ indexQuery(indexHandle, vector, k)→ int[]  (top-k IDs)
└─ complete(modelHandle, prompt)     → String
```

**Two separate index handle pairs, never mixed:**

| Name | ModelRegistry key | Index dir | Purpose |
|------|-------------------|-----------|---------|
| RAG model | `"rag"` | `/data/system/jarvis/index_rag_<engine>/` | Document chunk embeddings |
| Tools model | `"tools"` | `/data/system/jarvis/index_tools_<engine>/` | Tool description embeddings |

Same embedding model, different index directories, one set per engine (vectors
from one engine's embedding model mean nothing to another's). A third,
`"primary"`, is the chat model (`registerChatModel`). The separation is enforced
by ModelRegistry — you can only get a handle pair by name.

## Inference — Two Engines Behind One Door

Since October there are two engines, and nothing outside `inference/` knows
which one is running. Callers use `Inference`, which forwards to the selected
`InferenceEngine`:

| Engine | Source | Model files | Notes |
|--------|--------|-------------|-------|
| `cactus` | `vendor/cactus` (fork, branch `jarvis-v2.2.2`) | bundle folders: `/data/system/jarvis/models/cactus/<role>/` | Cactus v2 dropped .gguf; builds for x86_64 through the SIMDe shim (22 Oct) |
| `llama` | `vendor/llama.cpp` (fork, branch `jarvisos`) | `/data/system/jarvis/models/<role>.gguf` | runs on the phone and the emulator |

Roles are `embed` and `chat`. `persist.sys.jarvis.engine` is `auto` (default),
`cactus` or `llama`. `auto` takes the first engine, Cactus before llama.cpp,
that can run and has a chat model installed. On the x86_64 emulator Cactus is
never picked by `auto`, only by name. Handles belong to the engine that made
them, so switching engines reloads every model and index.

---

## ObjectBox — The Storage Layer

One store (`JarvisStore`), all entities. Store lives at `/data/system/jarvis/objectbox/`.

```
JarvisStore.box(SourceFile.class)     → Box<SourceFile>
JarvisStore.box(ToolRecord.class)     → Box<ToolRecord>
// etc.
```

**Entity map — what's stored where:**

| Entity | Package | What it represents |
|--------|---------|-------------------|
| `SourceFile` | model | Every file JarvisOS has seen |
| `DocumentChunk` | model | Indexed chunk of a SourceFile (pointer into Cactus index) |
| `Folder` | model | Directory metadata and summary |
| `Conversation` | model | A chat session |
| `Message` | model | One message in a Conversation |
| `UserContext` | model | Single-row persistent facts about the user |
| `AccessLog` | model | Which files were accessed in each conversation |
| `TaskMemory` | model | What approach was used for a past task |
| `AppRecord` | tools | An installed app that registered tools |
| `ToolRecord` | tools | One tool from an AppRecord |

**Note on `_` classes:** `AppRecord_`, `ToolRecord_`, `SourceFile_`, etc. are ObjectBox
query helpers — they let you write `.equal(ToolRecord_.toolName, "foo", ...)` in a
type-safe way instead of using raw strings. Currently hand-written stubs; will be
replaced by processor-generated versions when `objectbox-generator` is wired up.

---

## Sessions and the Tool Fence

A session lets Jarvis take part while an app runs, for example as the other
player in tic-tac-toe (`apps/JarvisTicTacToe`). The app declares the session in
its manifest (meta-data `com.jarvisos.session`), opens it through
`JarvisSession` while in the foreground, and sends **events** (data, never
prompt text). The manifest's turn event wakes Jarvis for one turn; Jarvis
answers by calling the session's tools or `say`. The full design is
`AGENT_EXPERIENCE.md` section 11.

`SessionManager` is also the `ToolDispatcher.Fence`: during a session only that
session's tools and `say` may run; outside one, session tools are hidden and
refused. The fence sits on the one route every tool call takes, so no prompt,
model or shell command gets past it. `persist.sys.jarvis.session_grammar`
controls grammar-constrained tool calls in sessions.

## History

`History` records each chat exchange and each app session as a
`Conversation` with `Message`s (source: text, voice, tool, event, say, end), for
the Jarvis app's History tab. Raw records are deleted after
`persist.sys.jarvis.history_hours` (default 24). Each record also writes an
`AgentSession`, which `DreamWorker` turns into lasting `UserContext` facts.

## The AIDL Interfaces — What Apps See

### IJarvisService ("jarvis")
```
processQuery(String query) → String                         // full query → response (tool or RAG)
processQueryWithImage(String query, byte[] image) → String
processQueryWithAudio(String query, byte[] pcm) → String    // 16 kHz mono 16-bit PCM
getStatus() → String                                        // JSON: engine, models, sessions, last trace
indexDocument(String path) → void
isIndexed(String path) → boolean
isReady() → boolean
listConversations() → String / getConversation(long id) → String / clearHistory()
```

### IToolRegistry ("jarvis_tools")
```
listTools() → String                     // JSON array of all registered tools
getTool(long id) → String                // JSON object for one tool by ObjectBox ID
searchTools(String query) → String       // semantic search, returns JSON array
callTool(String name, String argsJson) → String
```

### IJarvisSessionService ("jarvis_session") — no permission, own package only
```
startSession(String pkg, String session, String[] closedTools, IJarvisSessionCallback cb) → String id
sendEvent(String id, String event, String dataJson)
endSession(String id, String event, String dataJson)
```
Callback: `onTurnStarted`, `onSay`, `onTurnEnded`, `onSessionEnded`.

All three endpoints are published by `JarvisService.onStart()` and share its lifecycle.

---

## On-Disk Layout

```
/data/system/jarvis/
├── objectbox/          ← ObjectBox store (all entities)
├── models/
│   ├── embed.gguf, chat.gguf     ← llama.cpp models
│   ├── cactus/embed/, cactus/chat/   ← Cactus bundles
│   └── needle3.cact, whistle.cact    ← fast path and speech
├── index_rag_<engine>/   ← vector index for document chunks (cactus or llama)
├── index_tools_<engine>/ ← vector index for tool descriptions
└── index_tools_needle/ ← Needle's own tool index
```

On the build server, models for the emulator live in `/srv/jarvis/models`;
`jh models` installs any the emulator is missing.

---

## Where to Read Next

- `AGENT_EXPERIENCE.md` — sessions, events, the tool contract, what is built and what is not
- `TESTING.md` — the `jh` harness, test suites, the emulator
- `skills/jarvis-presentation/references/roadblocks.md` — every problem hit so far and how it was solved

The agent loop (Phase 5) is built: `agent/JarvisExecutor` with the nodes listed
above. Its original April design (`AGENTIC_LOOP.md`) is in the local
`archive/` folder, kept off GitHub as outdated.
