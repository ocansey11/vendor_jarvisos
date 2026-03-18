# AGENTS.md — JarvisOS Project Spec
> This file is the persistent memory and architectural spec for JarvisOS.
> Claude reads this at the start of every session. Kevin updates it after every sprint.
> Last updated: March 2026

---

## Project Vision

JarvisOS is a privacy-first Android ROM built on LineageOS that integrates local AI capabilities without cloud dependencies. It solves two critical privacy violations:
1. Android's constant tracking (devices ping Google servers every ~4.5 minutes, transmitting IMEI and hardware data)
2. Cloud-based LLMs that store user conversations for training

The goal: a complete privacy-first OS where the AI assistant runs entirely on-device.

---

## Team

- **Kevin** — Lead, Android system layer, RAG architecture, project vision
- **Sam** — NLP engineer
- **Gerald** — Security and configuration
- **Desmond** — Occasional contributor

---

## Repository Structure

| Repo | Path | Purpose |
|------|------|---------|
| LineageOS source | `~/android/lineage/` | Full LineageOS 21.0 source (~150GB, 1430 repos) |
| frameworks/base fork | `~/android/lineage/frameworks/base/` | Android framework — system services live here |
| vendor_jarvisos | `~/android/lineage/vendor/jarvisos/` | JarvisOS vendor overlay — config, prebuilts, this spec |
| cactus fork | `~/android/lineage/vendor/cactus/` | Forked Cactus inference engine with JarvisOS JNI bindings |

**Local manifest:** `~/android/lineage/.repo/local_manifests/jarvos.xml`
- `ocansey11/android_frameworks_base` → `frameworks/base` (branch: `lineage-23.0`)
- `ocansey11/vendor_jarvisos` → `vendor/jarvisos` (branch: `main`)
- `ocansey11/cactus` → `vendor/cactus` (branch: `main`, `sync-s=false`)

All repos track `JarvisOs/main` remote. No upstream remotes — no accidental pulls.

---

## Architecture Overview

```
User / App
    |
    | AIDL (Binder IPC)
    v
RagService.java  (System Service — frameworks/base/services/core/java/com/android/server/rag/)
    |
    +-- JarvisFileObserver     watches Documents/ Downloads/ for file changes
    |        |
    |        v
    |   IndexQueue             BlockingQueue (cap 500), pushes INDEX/REMOVE tasks
    |        |
    |        v
    |   RagIndexWorker         WorkManager job (15min, charging only)
    |        |
    |        +-- TextExtractor        file → raw text (.txt .md .csv .pdf .docx)
    |        +-- ChunkingStrategy     text → chunks  [TODO]
    |        +-- CactusWrapper.embed  chunk → float[]
    |        +-- ObjectBox            persist SourceFile, DocumentChunk entities
    |
    +-- processQuery()  (immediate, no constraints)
             |
             v
        MetadataSearch (Stage 1 — free, ObjectBox keyword/score search)
             |
             v
        CactusWrapper.embed + indexQuery (Stage 2 — semantic, expensive)
             |
             v
        CactusWrapper.complete → response string
```

**Key insight:** Two-stage retrieval. Stage 1 uses ObjectBox metadata search (free). Stage 2 embeds and does vector search only on the candidates from Stage 1 (expensive but bounded).

---

## Tech Stack

- **Base OS:** LineageOS 23.0 (Android 15)
- **Inference engine:** Cactus (local LLM, C++) — forked at `vendor/cactus`
- **Vector DB:** ObjectBox 4.0.3 with HNSW indexing (JarvisOS memory layer)
- **Embedding model:** Qwen / nomic-embed-text
- **Chat model:** Qwen
- **Function dispatch:** FunctionGemma (270M, zero-shot only)
- **Build system:** Soong (Android.bp)
- **Languages:** Java (services), C++ (JNI/Cactus), Python (tooling)

---

## File Map — RAG Service

All files in `frameworks/base/services/core/java/com/android/server/rag/`:

| File | Status | Purpose |
|------|--------|---------|
| `RagService.java` | ✅ Done | System service entry point, wires everything together |
| `RagManager.java` | ✅ Done | Public API manager |
| `IJarvisService.aidl` | ✅ Done | AIDL interface |
| `JarvisFileObserver.java` | ✅ Done | Watches Documents/ Downloads/ |
| `IndexQueue.java` | ✅ Done | Singleton BlockingQueue, max 500 |
| `RagIndexWorker.java` | ✅ Done | WorkManager job, drains queue, calls Cactus |
| `TextExtractor.java` | ✅ Done | File → text (.txt .md .csv .pdf .docx) |
| `MetadataSearch.java` | ✅ Done | Stage 1 retrieval, ObjectBox keyword scoring |
| `CactusWrapper.java` | ✅ Done | JNI bridge to libcactus.so |
| `ChunkingStrategy.java` | ✅ Done | Sentence-boundary splitting, overlap carry-over, singleton |
| `Chunk.java` | ✅ Done | Data class passed between ChunkingStrategy → RagIndexWorker |
| `VectorStore.java` | 🔄 TODO | ObjectBox HNSW vector ops wrapper |
| `Android.bp` | ✅ Done | Build config — wires in ObjectBox, WorkManager |

**ObjectBox entities** (all in `rag/` package):
`SourceFile`, `DocumentChunk`, `Folder`, `Conversation`, `Message`, `UserContext`, `AccessLog`, `TaskMemory`

---

## Cactus Fork — What We Added

`vendor/cactus/Android.bp` — Soong build file compiling `libcactus.so` from all C++ sources.

`vendor/cactus/android/cactus_jni.cpp` — Added JarvisOS system service bindings at the bottom:
- `Java_com_android_server_rag_CactusWrapper_nativeInit` (+ `cacheIndex` bool param)
- `Java_com_android_server_rag_CactusWrapper_nativeDestroy`
- `Java_com_android_server_rag_CactusWrapper_nativeEmbed`
- `Java_com_android_server_rag_CactusWrapper_nativeIndexInit/Add/Query/Delete/Destroy`
- `Java_com_android_server_rag_CactusWrapper_nativeComplete`
- `Java_com_android_server_rag_CactusWrapper_nativeGetLastError`

All static methods (`jclass` not `jobject`) — no Kotlin runtime dependency.

---

## Build System

`vendor/jarvisos/products/jarvisos.mk`:
```makefile
PRODUCT_PACKAGES += libcactus libobjectbox-jni
```

This pulls both native libraries into the system image so `CactusWrapper.java`'s `System.loadLibrary("cactus")` works at runtime.

**TODO:** Wire `jarvisos.mk` into the device product config (`$(call inherit-product, vendor/jarvisos/products/jarvisos.mk)`).

---

## Development Phases

### ✅ Phase 0 — Foundation
- LineageOS source set up, vendor overlay initialized
- AIDL interfaces drafted, manifest entries added

### ✅ Phase 1 — RAG Service Architecture
- `RagService` registered as system service in `SystemServer.java`
- Binder IPC working via AIDL
- `JarvisFileObserver` → `IndexQueue` → `RagIndexWorker` pipeline wired
- `TextExtractor`, `MetadataSearch`, `CactusWrapper` implemented
- ObjectBox entities defined (8 entities, two-stage retrieval schema)
- ObjectBox wired into `Android.bp`

### ✅ Phase 2 — Core RAG Pipeline (COMPLETE)
- [x] ObjectBox store initialized in `RagService` via `JarvisStore.init(STORE_DIR)`
- [x] `Chunk.java` — proper data class (was duplicate ChunkingStrategy)
- [x] `ChunkingStrategy.java` — fully implemented, sentence-boundary + overlap, singleton
- [x] `MetadataSearch.java` — all 6 passes wired to real ObjectBox queries (alias/tags/fileName/Folder/TaskMemory/AccessLog)
- [x] `RagIndexWorker.java` — full pipeline: hash check → TextExtractor → ChunkingStrategy → embed → indexAdd → ObjectBox persist; REMOVE task implemented
- [x] `RagManager.java` — package fixed to `android.app.rag`, `isIndexed()` added, `requireService()` pattern
- [x] `IRagService.aidl` — package fixed to `android.app.rag`, `isIndexed()` added
- [x] `RagService.java` — `isIndexed()` implemented in Binder stub

### 🔄 Phase 3 — Build Verification + Cactus Exploration (NEXT)
- [ ] Wire `jarvisos.mk` into device product config (`$(call inherit-product, ...)`)
- [ ] Run `m libcactus` — verify clean Soong compile
- [ ] Explore Cactus source: `engine/`, `kernel/`, `ffi/`, `models/` folders (only `graph/` explored so far)
- [ ] Understand Cactus embedding pipeline end-to-end before wiring CactusWrapper handles into RagService
- [ ] End-to-end indexing test on real device

### Phase 4 — Tool Registry (Android-native MCP)

#### Problem
FunctionGemma (270M) cannot reason over 240+ tools (60 apps × 4 tools). Must filter to top-k before any model sees them.

#### Two-layer discovery model

**Layer 1 — First-party / curated (no app cooperation needed)**
JarvisOS ships built-in tool definitions for major apps (WhatsApp, Gmail, Google Maps etc.) stored in `vendor/jarvisos/tools/<packageName>.json`. `ToolScanner` loads these at boot. No manifest changes needed from app developers.

**Layer 2 — Third-party / declared (apps that want native support)**
Apps declare tools via Android's standard `<meta-data>` tag inside `AndroidManifest.xml`. This is a proven pattern used by Firebase, Crashlytics, AdMob — guaranteed available after install via `PackageManager.GET_META_DATA`.

Convention: one `<meta-data>` entry per tool, value is a JSON blob:
```xml
<meta-data
    android:name="ai.jarvisos.tool.send_message"
    android:value='{"name":"send_message","description":"Sends a WhatsApp message","params":["recipient","message"]}'/>
```
`ToolScanner` iterates all installed packages, reads `metaData` bundle, collects any key matching `ai.jarvisos.tool.*`.

**Open question — naming convention:** `ai.jarvisos.tool.*` prefix requires app developers to know they're building for JarvisOS. A more generic protocol (e.g. a dedicated `res/xml/jarvis_tools.xml` file inside the APK, or a `JarvisToolProvider` ContentProvider) would allow adoption without OS-specific naming. Not yet decided — needs design session with Sam.

#### ObjectBox schema (tool registry IS ObjectBox — no SQLite)

```java
@Entity
public class RegisteredApp {
    @Id long id;
    String packageName;      // com.whatsapp
    String appLabel;         // WhatsApp
    String sourceType;       // "curated" | "declared"
    long lastScanTime;
    boolean isActive;        // false = uninstalled
}

@Entity
public class RegisteredTool {
    @Id long id;
    String toolName;         // send_message
    String description;      // full natural language description
    String paramsJson;       // ["recipient", "message"]
    String rawDefinition;    // full string used for embedding
    @HnswIndex(dimensions = 1024)
    float[] embedding;       // semantic vector for top-k filtering
    ToOne<RegisteredApp> app; // FK → which app owns this tool
}
```

#### Query-time tool filtering
1. Embed user query via Cactus
2. HNSW search on `RegisteredTool.embedding` → top-k tools (e.g. 5–10)
3. Each result carries `ToOne<RegisteredApp>` — knows which app, which tool
4. Format top-k as JSON function definitions → inject into Cactus completion call
5. FunctionGemma selects one → `ToolDispatcher` fires the appropriate Intent/ContentProvider call

#### Tool lifecycle
- **Install:** `ToolScanner` reads meta-data, embeds definitions, persists `RegisteredApp` + `RegisteredTool` to ObjectBox
- **Update:** Delete old `RegisteredTool` entries for that package, re-scan, re-embed
- **Uninstall:** Delete all `RegisteredTool` where `app.packageName == uninstalledPackage`, delete `RegisteredApp`

#### Files
```
frameworks/base/services/core/java/com/android/server/rag/tools/
    ToolScanner.java       — install/update/delete lifecycle, meta-data reader
    ToolDispatcher.java    — receives FunctionGemma output, resolves + fires Intent
    IToolRegistry.aidl     — public API: listTools(), getTool(id), executeTool(id, argsJson)
vendor/jarvisos/tools/
    com.whatsapp.json      — curated first-party tool definitions
    com.google.android.gm.json
    com.google.android.apps.maps.json
```

#### Separate Cactus index for tools
Tool embeddings use a separate `(modelHandle, indexHandle)` pair from RAG document embeddings. Same model (Qwen/nomic) but separate index directory — `/data/system/jarvis/index_tools/`. Indexes must never be mixed.

#### Tool Registry — ObjectBox schema
Two entities. `AppRecord` is the umbrella per installed app. `ToolRecord` is one tool, linked to its app via `ToOne<AppRecord>`.

```java
@Entity
public class AppRecord {
    @Id long id;
    String packageName;   // com.borderless.app
    String appLabel;      // Borderless
    long lastScanTime;
    boolean isActive;     // false if uninstalled
}

@Entity
public class ToolRecord {
    @Id long id;
    String toolName;       // find_lawyer
    String description;    // "Finds immigration lawyers near a location"
    String paramsJson;     // ["location", "specialty"]
    String rawDefinition;  // full string used for embedding
    @HnswIndex(dimensions = 1024)
    float[] embedding;     // semantic search vector
    ToOne<AppRecord> app;  // FK → owning app
}
```

**Query flow:** embed user query → HNSW search on `ToolRecord.embedding` → top-k results carry `ToOne<AppRecord>` so Jarvis knows exactly which app owns each tool → format + inject into Cactus context.

**Lifecycle:**
- Install → `tool_scanner` scans manifest meta-data, creates `AppRecord` + `ToolRecord` entries, embeds each tool definition
- Update → delete old `ToolRecord` entries for that package, re-scan, re-embed
- Uninstall → delete all `ToolRecord` where `app.packageName == uninstalledPackage`, delete `AppRecord`

#### Tool discovery protocol — open design problem
How third-party apps declare tools is unresolved. Two confirmed approaches:

**Layer 1 — First party / curated (no app cooperation needed)**
Jarvis ships built-in tool definitions for major apps (WhatsApp, Gmail, Google Maps etc.) in `vendor/jarvisos`. Based on existing intents and Content Providers. Works immediately, no developer action required.

**Layer 2 — Third party / declared (app opts in)**
`<meta-data>` in `AndroidManifest.xml` is the confirmed Android-native mechanism — used by Crashlytics, Google AdMob, Firebase. PackageManager exposes it via `getApplicationInfo(pkg, GET_META_DATA).metaData` after install. Guaranteed available.

The open question is **naming convention** — apps have different package prefixes so `tool_scanner` can't filter by prefix. Options under consideration:
- Fixed keyword in meta-data name: `android:name="com.whatsapp.jarvis_tool.send_message"` → scanner filters for `jarvis_tool.` substring
- Fixed Jarvis key, structured JSON value: `android:name="android.tool.provider"` with JSON blob as value
- Dedicated `res/xml/jarvis_tools.xml` file inside APK, referenced via single meta-data line
- `JarvisToolProvider` Content Provider — richer, more dynamic, standard Android pattern

**Not decided yet.** Needs protocol spec before Phase 4 implementation. Discuss with Sam.

**Key constraint:** The model that indexed the tool embeddings must be the model that queries them. Embeddings from different models are not cross-comparable — enforced by `(modelHandle, indexHandle)` pairing.

### Phase 5 — Voice + Context Switching
- Wake word detection
- Audio capture at system level
- TTS response
- Quiz mode, interview sim, study mode

---

## MD Maintenance Protocol

At the **end of every session**, Claude must:
1. Update AGENTS.md — file map, phase status, session log
2. Rewrite HANDOFF.md — completed + next sections only (don't append)
3. Update PROGRESS.md — if learning happened
4. Check for `CHECKPOINT.md` — if exists, merge into AGENTS.md then delete it

**Size discipline:** AGENTS.md = architecture only. HANDOFF.md = current session only. Never let MDs grow into logs.

---

## Key Principles

1. **Build reusable features in `lib/` as package code, not example-specific.** Only UI goes in `example/lib`. Extend Cactus, don't reinvent it.
2. **Small models = zero-shot only.** No system prompt injection for sub-1B models.
3. **Deterministic post-processing > keyword routing.**
4. **Semantic chunking > naive splitting.** Sliding window similarity detection.
5. **Embeddings at send-time, not upload-time.**
6. **JarvisOS owns the memory layer. Cactus owns inference.**
7. **Follow Android's security model.** Public APIs separate from privileged system services.
8. **We manage our own indexes — not Cactus's corpus_dir.** Cactus's built-in RAG is static, single-model, app-level. JarvisOS needs dynamic, multi-model, OS-level. We use Cactus primitives (`indexInit`, `indexAdd`, `indexQuery`, `embed`) and build our own orchestration on top.
9. **Multiple models = multiple handle pairs.** Each model gets its own `(modelHandle, indexHandle)` and its own index directory on disk. Indexes are not shared across models — embedding dimensions must match. Current: one pair (Qwen, conversations). Phase 4 adds a second pair (tools).
10. **`CactusWrapper.java` is the abstraction boundary.** If Cactus ever needs to be swapped, only that file and the JNI layer changes. ObjectBox schema, RagService pipeline, and FileObserver are unaffected.
11. **Audio models are preprocessing, not retrieval.** Whisper/Moonshine produce transcripts, not stored embeddings. Flow: audio → Whisper → text → Qwen embed → index_conversations.
12. **Model Registry pattern (Phase 4 prerequisite).** Before adding a second model, replace static handles in `RagIndexWorker` with a named map: `{ name → (modelHandle, indexHandle, dim, indexDir) }`. Adding a new model then becomes a config entry, not a code change.

---

## Known Gaps / Future Work

| Item | Notes |
|------|-------|
| MetadataSearch semantic fallback | If ObjectBox returns zero candidates (e.g. file named `BU_exam_results_2024.pdf` with no tags, query asks about "biology test") — no cactusIndexIds are passed to Cactus, result is empty. Fix: if MetadataSearch confidence below threshold, fall back to full Cactus vector scan. Expensive but only triggered when cheap path fails. Pattern: `MetadataSearch → candidates? → yes: precision retrieval / no: full scan fallback`. Phase 3 item. |
| GraphRAG not yet implemented | Current architecture is two-stage retrieval (ObjectBox metadata + Cactus precision vector search) — an optimisation on top of traditional RAG, not GraphRAG. GraphRAG requires: entity extraction at index time, relationship mapping between entities, graph storage (nodes + edges), and multi-hop traversal at query time. ObjectBox already has partial scaffolding (TaskMemory, Folder relationships) but extraction and traversal logic is missing. The GraphRAG diagram in the presentation should be framed as "where we're headed" not "what we built". Note: embeddings from different models are not cross-comparable — each model must query only the index it created. This is already enforced by the (modelHandle, indexHandle) pairing. Phase 4/5 item — discuss with Sam. |

---

## Milestones

| Milestone | Status |
|-----------|--------|
| LineageOS source setup | ✅ Done |
| Vendor overlay init | ✅ Done |
| AIDL interfaces + system service registration | ✅ Done |
| Phase 1 RAG service architecture | ✅ Done |
| Cactus fork + JarvisOS JNI bindings | ✅ Done |
| Android.bp for libcactus | ✅ Done |
| ObjectBox store initialized | 🔄 Next |
| m libcactus clean compile | 🔄 Next |
| End-to-end indexing pipeline | 📅 Planned |
| March 2025 presentation | 🎯 Target |

---

## Session Log

| Date | What happened |
|------|--------------|
| Early 2026 | Phase 0 complete, AIDL + manifest work done |
| Hackathon | Won 2nd place Google DeepMind x Cactus — tool calling optimization, semantic chunking |
| Feb 2026 | dev.talk speaker slot confirmed |
| Mar 2026 session 1 | Reconnected, filesystem access established, AGENTS.md created, Phase 1 scoped |
| Mar 2026 session 2 | Phase 1 complete — RagService, FileObserver, IndexQueue, RagIndexWorker, TextExtractor, MetadataSearch, CactusWrapper, ObjectBox entities all implemented and committed |
| Mar 2026 session 3 | Cactus fork explored — existing JNI layer discovered, JarvisOS bindings added to cactus_jni.cpp, Android.bp written, jarvisos.mk created, all vendor repos locked to JarvisOs/main |
| Mar 2026 session 4 | Git emergency — all commits were on lineage-21.0 pointing at LineageOS upstream instead of fork. Cherry-picked all 5 commits onto lineage-23.0, resolved Android.bp + RagService.java conflicts, pushed to origin. Fixed conflict markers left in RagService.java, removed stale AndroidObjectBrowser import from JarvisStore.java, corrected LineageOS version in AGENTS.md. Branch tracking now correct. |
| Mar 2026 session 5 | Phase 2 complete — Chunk.java fixed, ChunkingStrategy implemented, MetadataSearch all 6 ObjectBox passes wired, RagIndexWorker full pipeline implemented, RagManager + IRagService package fixed to android.app.rag, isIndexed() added across full Binder stack. AGENTS.md updated. Handoff note written for next Claude session. |
| Mar 2026 session 6 | Presentation work + protocol design. Memory slides: two-slide structure agreed (GraphRAG as "where we're headed", JarvisOS two-stage retrieval as current implementation). HNSW clarification — ObjectBox runs no LLM inference, uses HNSW graph index for ANN search. MetadataSearch semantic fallback gap identified and added to Known Gaps. GraphRAG not yet implemented — added to Known Gaps. Tool Registry design: reviewed Sam's protocol doc, confirmed ObjectBox over SQLite, `<meta-data>` confirmed as Android-native mechanism (used by Crashlytics/AdMob/Firebase). AppRecord + ToolRecord ObjectBox schema designed. Tool discovery protocol (naming convention for third-party app declarations) is open design problem — not decided, needs spec before Phase 4. Borderless and InventoryTracker used as concrete examples throughout. |
