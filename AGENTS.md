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

- **Base OS:** LineageOS 21.0 (Android 14)
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
| `ChunkingStrategy.java` | 🔄 TODO | Text → chunks |
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

### 🔄 Phase 2 — Core RAG Pipeline (CURRENT)
- [ ] Initialize ObjectBox store in `RagService`
- [ ] Implement `ChunkingStrategy.java`
- [ ] Implement `VectorStore.java`
- [ ] Wire ObjectBox queries in `MetadataSearch` (remove TODOs)
- [ ] Wire `jarvisos.mk` into device product config
- [ ] Run `m libcactus` — verify clean compile
- [ ] End-to-end indexing test

### Phase 3 — Voice + Context Switching
- Wake word detection
- Audio capture at system level
- TTS response
- Quiz mode, interview sim, study mode

---

## Key Principles

1. **Build reusable features in `lib/` as package code, not example-specific.** Only UI goes in `example/lib`. Extend Cactus, don't reinvent it.
2. **Small models = zero-shot only.** No system prompt injection for sub-1B models.
3. **Deterministic post-processing > keyword routing.**
4. **Semantic chunking > naive splitting.** Sliding window similarity detection.
5. **Embeddings at send-time, not upload-time.**
6. **JarvisOS owns the memory layer. Cactus owns inference.**
7. **Follow Android's security model.** Public APIs separate from privileged system services.

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
