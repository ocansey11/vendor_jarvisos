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

| Repo | Purpose |
|------|---------|
| `android/lineage/` | Full LineageOS 21.0 source (~150GB, 1430 repos) |
| `android/lineage/frameworks/base/` | Android framework — where system services live |
| `vendor_jarvisos/` | JarvisOS vendor overlay — config, overlays, this spec |
| `functiongemma-hackathon/` | Hackathon tool-calling work (reference) |
| `rag-backup/` | RAG pipeline backup (reference) |

---

## Architecture Overview

```
User / App
    |
    | AIDL (Binder IPC)
    v
JarvisService (System Service — frameworks/base/services/)
    |
    | JNI
    v
CactusWrapper (C++ — connects to Cactus inference engine)
    |
    v
Local LLM (Qwen / Gemma — on device, no cloud)
    |
    v
ObjectBox Vector Store (metadata + memory layer — JarvisOS owned)
    |
    v
Cactus internal ObjectBox (raw vector ops — Cactus owned)
```

**Key insight:** Cactus already ships with its own ObjectBox internally. JarvisOS does NOT reinvent this. JarvisOS owns the **metadata and memory layer** — the context of the user's life (conversations, documents, preferences). Cactus handles inference and raw vector operations.

---

## Tech Stack

- **Base OS:** LineageOS 21.0 (Android 14)
- **Inference engine:** Cactus (local LLM, C++)
- **Vector DB:** ObjectBox 4.0.3 with HNSW indexing (JarvisOS memory layer)
- **Embedding model:** Qwen / nomic-embed-text
- **Chat model:** Qwen
- **Function dispatch:** FunctionGemma (270M, zero-shot only)
- **Build system:** Android build system (Soong/Blueprint)
- **Languages:** Java/Kotlin (services), C++ (JNI/Cactus), Python (tooling)

---

## Development Phases

### ✅ Phase 0 — Foundation
- LineageOS source set up
- Vendor overlay initialized
- Manifest entries added
- AIDL interfaces drafted

### 🔄 Phase 1 — Background Service "Hello World" (CURRENT)
**Goal:** Prove the system service architecture works end-to-end.

**Scope:**
- `JarvisService.java` — system service, starts on boot
- AIDL interface for apps to communicate with the service
- Binder IPC working
- Text-in, text-out (no voice yet)
- Stubbed LLM response (or simple local inference)
- Registered in `manifest_services.xml`

**Voice:** Deferred to Phase 2. Voice is a client layer on top of this pipe, not part of the pipe itself.

**Location:** `frameworks/base/services/core/java/com/android/server/rag/` — NOTE: package is named `rag` but this IS the Jarvis service. Rename deferred until system is working.

### Phase 2 — Core RAG Pipeline
- `ChunkingStrategy.java` — document processing
- `VectorChunk.java` — ObjectBox entities
- `VectorStore.java` — semantic search
- ObjectBox 4.0.3 + HNSW integration
- Real on-device inference via CactusWrapper

### Phase 3 — CactusWrapper JNI Bridge
- Java ↔ C++ bridge to Cactus inference engine
- Android background service support added to upstream Cactus
- Kevin becomes a credible Cactus contributor

### Phase 4 — Voice + Context Switching
- Wake word detection
- Audio capture at system level
- TTS response
- Quiz mode, interview sim, study mode

---

## Key Principles

1. **Build reusable features in `lib/` as package code, not example-specific.** Only UI goes in `example/lib`. Extend Cactus, don't reinvent it.
2. **Small models = zero-shot only.** No system prompt injection for sub-1B models — put all logic in Python/Java post-processing.
3. **Deterministic post-processing > keyword routing.** Catalogue failure modes, write deterministic fixes.
4. **Semantic chunking > naive splitting.** Sliding window similarity detection for multi-intent queries.
5. **Embeddings at send-time, not upload-time.**
6. **JarvisOS owns the memory layer. Cactus owns inference.**
7. **Follow Android's security model.** Public APIs separate from privileged system services.

---

## Milestones

| Milestone | Status |
|-----------|--------|
| LineageOS source setup | ✅ Done |
| Vendor overlay init | ✅ Done |
| AIDL interfaces drafted | ✅ Done |
| Manifest entry added | ✅ Done |
| Phase 1 service running on device | 🔄 In progress |
| Memory entities + FileObserver | ✅ Done |
| March 2025 presentation | 🎯 Target |
| Cactus upstream contribution | 📅 Planned |

---

## Session Log

| Date | What happened |
|------|--------------|
| Early 2026 | Phase 0 complete, AIDL + manifest work done |
| Hackathon | Won 2nd place Google DeepMind x Cactus — tool calling optimization, semantic chunking |
| Feb 2026 | dev.talk speaker slot confirmed |
| Mar 2026 | Reconnected, filesystem access established, AGENTS.md created, Phase 1 scoped |
| Mar 2026 | Memory schema designed, 8 ObjectBox entities + FileObserver implemented in `rag/`, ObjectBox wired into Android.bp |
| Mar 2026 | Full codebase walkthrough: RagService, MetadataSearch, RagIndexWorker, TextExtractor, ChunkingStrategy, JarvisFileObserver. Bugs identified and some patched. TOOL_REGISTRY.md created. |

---

## Known Gaps (identified during walkthrough)

- **JarvisFileObserver does not recurse into subdirectories** — only watches flat `Documents/` and `Downloads/`. Files in subfolders are invisible. Fix: walk directory tree on startup, spin up one observer per subdirectory.
- **Pictures folder not watched** — users store images in `/Pictures`. Needs adding to `WATCH_PATHS`. Image text extraction (OCR) is a separate TODO.
- **TextExtractor: PDF extraction is raw byte scraping** — works only on uncompressed PDFs. Proper fix: add Apache PDFBox to `vendor/jarvisos/prebuilts`.
- **TextExtractor: DOCX extraction is unimplemented** — needs Apache POI in prebuilts.
- **TextExtractor: 10MB hard cap silently skips large files** — should stream-extract instead.
- **RagService init order was wrong** — fixed: ObjectBox now initialises before FileObserver starts.
- **RagIndexWorker Cactus handles are static/local** — Phase 3: move to RagService ownership, inject into worker.
- **enforceCallingPermission() is a stub** — `android.permission.ACCESS_RAG_SERVICE` not yet enforced.
- **MetadataSearch Pass 6 runs N ObjectBox queries in a loop** — optimise to single bulk query when doing perf pass.
- **ChunkingStrategy splits on abbreviations** (Dr., U.S.) — minor noise, fix in later iteration.
- **cactusId generation** (`existing.id * 10000 + chunkPosition`) assumes max 9999 chunks per file.
- **CactusWrapper JNI bring-up** — `libcactus.so` must be compiled with Android NDK targeting `arm64-v8a`. Likely failure modes on first device bring-up: `UnsatisfiedLinkError` (wrong path/ABI), native calls returning 0L (symbol export mismatch), `embed()` returning garbage (embedding dimension mismatch). Expect a dedicated bring-up sprint. x86 emulators cannot be used — ARM hardware only.

---

## Next Actions (Phase 1 Sprint)

- [ ] Create `frameworks/base/services/jarvis/` directory
- [ ] Write `JarvisService.java`
- [ ] Write AIDL interface `IJarvisService.aidl`
- [ ] Register in `manifest_services.xml`
- [ ] Wire into `SystemServer.java`
- [ ] Test with a simple client app
- [ ] Presentation prep
- [ ] Blog post draft
