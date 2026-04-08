# JarvisOS Architecture — Service Internals
> For Sam and anyone building on top of or inside the Jarvis service layer.
> Assumes you know: Jarvis is a privileged Android system service. Cactus is the inference engine.

---

## The Two Layers

JarvisOS code lives in two places in `frameworks/base/`:

```
frameworks/base/
│
├── core/java/android/jarvis/          ← PUBLIC API (what apps see)
│   ├── IRagService.aidl               ← query interface: processQuery, indexDocument, isIndexed, isReady
│   ├── IToolRegistry.aidl             ← tool interface: listTools, getTool, searchTools
│   ├── RagManager.java                ← convenience wrapper around IRagService (like LocationManager)
│   └── RagException.java             ← checked exception for API errors
│
└── services/core/java/com/android/server/jarvis/   ← SYSTEM SERVICE (privileged, inside system_server)
    └── (everything below)
```

**Rule:** Apps never import from `server/jarvis/`. They bind to the service via `RagManager`
which calls through `IRagService` / `IToolRegistry` Binder stubs. The service layer never
leaks its internal classes to callers.

---

## System Service Layer — Full Tree

```
com/android/server/jarvis/
│
├── RagService.java               ← THE ENTRY POINT — extends SystemService
│                                    registered in SystemServer.java at boot
│                                    publishes two Binder endpoints:
│                                      "rag"          → IRagService.Stub
│                                      "jarvis_tools" → IToolRegistry.Stub
│
├── IRagService.aidl              ← server-side copy of the Binder stub
│
├── core/                         ← SHARED INFRASTRUCTURE (used by everything)
│   ├── JarvisStore.java          ← ObjectBox singleton — one store, all entities
│   ├── ModelRegistry.java        ← named (modelHandle, indexHandle) pairs for Cactus
│   ├── IndexQueue.java           ← BlockingQueue<IndexTask> cap 500, singleton
│   ├── RagManager.java           ← internal API manager
│   ├── MyObjectBox.java          ← ObjectBox store builder (hand-written stub)
│   └── RagException.java
│
├── inference/
│   └── CactusWrapper.java        ← THE ONLY DOOR TO CACTUS — all JNI calls go here
│
├── indexing/                     ← PIPELINE: file → chunks → embeddings → ObjectBox
│   ├── JarvisFileObserver.java   ← inotify watcher on Documents/ Downloads/ Pictures/
│   ├── RagIndexWorker.java       ← WorkManager job — drains IndexQueue, embeds, persists
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
│   │
│   ├── SourceFile_.java          ← ObjectBox query helper (hand-written stub)
│   ├── Folder_.java
│   ├── AccessLog_.java
│   └── TaskMemory_.java
│
└── tools/                        ← TOOL REGISTRY — Phase 4
    ├── AppRecord.java            ← one ObjectBox row per installed app with tools
    ├── ToolRecord.java           ← one ObjectBox row per tool (FK → AppRecord)
    ├── ToolScannerService.java   ← scans APKs on install, embeds tool descriptions
    ├── ToolDispatcher.java       ← semantic search → tool selection → broadcast → result
    ├── AppRecord_.java           ← ObjectBox query helper (hand-written stub)
    └── ToolRecord_.java
```

---

## How the Subsystems Connect

### Boot sequence (RagService.initializeAsync)

```
RagService.onStart()
    │
    ├─ publishBinderService("rag", mBinder)            registers IRagService endpoint
    ├─ publishBinderService("jarvis_tools", ...)       registers IToolRegistry endpoint
    │
    └─ initializeAsync() [background thread]
           │
           1. JarvisStore.init()                       opens ObjectBox at /data/system/jarvis/objectbox/
           2. ModelRegistry.register("rag",   ...)     CactusWrapper.init() → modelHandle
              ModelRegistry.register("tools", ...)     CactusWrapper.indexInit() → indexHandle
           3. JarvisFileObserver.startWatching()       watches Documents/ Downloads/ Pictures/
           4. RagIndexWorker.schedule()                WorkManager (15min, charging only)
           5. ToolScannerService.start()               scans installed packages, embeds tools
```

### Query path (what happens when an app calls processQuery)

```
App → RagManager.processQuery(query)
        │  (Binder IPC across process boundary)
        ▼
RagService.mBinder.processQuery(query)
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
RagIndexWorker (WorkManager, 15min intervals, charging only)
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
| RAG model | `"rag"` | `/data/system/jarvis/index_rag/` | Document chunk embeddings |
| Tools model | `"tools"` | `/data/system/jarvis/index_tools/` | Tool description embeddings |

Same model file (embed.gguf), different index directories. The separation is enforced
by ModelRegistry — you can only get a handle pair by name.

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

## The AIDL Interfaces — What Apps See

### IRagService ("rag")
```
processQuery(String query) → String      // full query → response (tool or RAG)
indexDocument(String path) → void        // manually trigger indexing of a file
isIndexed(String path) → boolean         // check if a file has been embedded
isReady() → boolean                      // service boot check
```

### IToolRegistry ("jarvis_tools")
```
listTools() → String                     // JSON array of all registered tools
getTool(long id) → String                // JSON object for one tool by ObjectBox ID
searchTools(String query) → String       // semantic search, returns JSON array
```

Both endpoints are published by `RagService.onStart()` and share its lifecycle.

---

## On-Disk Layout

```
/data/system/jarvis/
├── objectbox/          ← ObjectBox store (all entities)
├── models/
│   └── embed.gguf      ← shared embedding + chat model
├── index_rag/          ← Cactus HNSW index for document chunks
└── index_tools/        ← Cactus HNSW index for tool descriptions
```

---

## What Phase 5 Adds

Phase 5 (Agentic Loop) sits on top of everything above without modifying it.
`JarvisExecutor` will call `RagService` via Binder for retrieval and
`ToolDispatcher` for tool dispatch — no coupling into RAG internals.
New entities (`AgentSession`, `AgentTurn`) join the same ObjectBox store.

See `AGENTIC_LOOP.md` for the full design.
