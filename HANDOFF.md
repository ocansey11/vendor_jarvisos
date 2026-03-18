# HANDOFF.md — Session 5 → Session 6
> Written by Claude at end of Session 5 (March 2026).
> Next Claude: read this first, then AGENTS.md, then the files listed below.
> Kevin has memory issues so this file bridges the gap between sessions.

---

## MD Maintenance Protocol (read every session)

At the **end of every session**, Claude must:

1. **Update AGENTS.md** — file map statuses, phase completion, session log entry
2. **Update HANDOFF.md** — rewrite "What was completed" + "What comes next" sections
3. **Update PROGRESS.md** — if any learning/quizzing happened, log concept + quiz result
4. **Check for a CHECKPOINT.md** at `~/android/lineage/vendor/jarvisos/CHECKPOINT.md`
   - If it exists: merge its contents into AGENTS.md where relevant, then delete it
   - If it doesn't exist: no action needed

### What is CHECKPOINT.md?
A scratch file Kevin or Claude can write mid-session to capture decisions, half-finished thoughts, or implementation notes that aren't ready for AGENTS.md yet. Think of it as a sticky note. Once it's been merged into AGENTS.md it gets deleted — it should never accumulate.

### MD size discipline
- **AGENTS.md** — architectural truth only. No session chatter. If a section grows too long, summarise it.
- **HANDOFF.md** — current session only. Rewrite it each session, don't append.
- **PROGRESS.md** — quiz log only. One row per concept. Keep it tabular.
- **CURRICULUM.md** — update active track + assigned reading only.

---

## How to start the session

### Step 1 — Read these files in order
```
\\wsl.localhost\Ubuntu-24.04\home\kevin\android\lineage\vendor\jarvisos\AGENTS.md
\\wsl.localhost\Ubuntu-24.04\home\kevin\android\lineage\vendor\jarvisos\HANDOFF.md   ← this file
\\wsl.localhost\Ubuntu-24.04\home\kevin\learning\CURRICULUM.md
\\wsl.localhost\Ubuntu-24.04\home\kevin\learning\java\PROGRESS.md
```

### Step 2 — Confirm git state is clean
Ask Kevin to run:
```bash
cd ~/android/lineage/frameworks/base && git log --oneline -5 && git branch -vv
cd ~/android/lineage/vendor/jarvisos  && git log --oneline -3 && git branch -vv
cd ~/android/lineage/vendor/cactus    && git log --oneline -3 && git branch -vv
```
Expected: all on correct branches, no conflict markers anywhere.

### Step 3 — Ask Kevin what mode he wants
Kevin works in explicit modes. Always ask at session start:
- **Code Sprint** — just build things, minimal explanation
- **Teaching** — concepts + quiz, one at a time
- **Explanation** — understand something deeply before touching code
- **Review** — read through files together and audit them

Kevin's note at end of Session 5: *"Full Review + Cactus Exploration"*

---

## What was completed this session (Session 6)

### Presentation work
- Two memory slides scoped: slide 1 = problem with naive RAG + two-stage retrieval design, slide 2 = current implementation as placeholder pending GraphRAG
- Slide 1 copy corrected: "no LLM inference, no embedding call" replaces "no model, no compute" — HNSW explained as graph index not neural model
- PNG export of JarvisOS memory architecture diagram generated for slide insertion
- GraphRAG gap identified and logged — current system is optimised traditional RAG, not GraphRAG
- MetadataSearch semantic fallback gap identified and logged

### Tool Registry architecture designed (Phase 4)
- Full two-layer discovery model designed: Layer 1 = curated first-party (vendor/jarvisos/tools/), Layer 2 = declared via `<meta-data>` tag
- `<meta-data>` confirmed as correct Android mechanism — used by Firebase, Crashlytics, AdMob, guaranteed post-install
- ObjectBox confirmed as sole storage layer — no SQLite needed
- `RegisteredApp` + `RegisteredTool` entity schema designed with `ToOne` relationship and HNSW index on tool embeddings
- Query-time filter flow designed: embed query → HNSW top-k → inject into Cactus → FunctionGemma selects
- Naming convention open question logged: `ai.jarvisos.tool.*` requires OS-specific knowledge from devs — ContentProvider or `res/xml/jarvis_tools.xml` may be more generic
- AGENTS.md Phase 4 section fully rewritten

### Known gaps added to AGENTS.md
- MetadataSearch semantic fallback (Phase 3)
- GraphRAG not yet implemented (Phase 4/5)

---

## What was completed this session (Session 5)

### Phase 2 — DONE ✅
All files in `frameworks/base/services/core/java/com/android/server/rag/`:

| File | What changed |
|------|-------------|
| `Chunk.java` | Was a duplicate ChunkingStrategy — rewritten as proper data class |
| `ChunkingStrategy.java` | Fully implemented: sentence-boundary splitting, OVERLAP_SIZE=50, singleton |
| `MetadataSearch.java` | All 6 ObjectBox passes wired (alias/tags/fileName/Folder/TaskMemory/AccessLog) |
| `RagIndexWorker.java` | Full pipeline: hash check → TextExtractor → ChunkingStrategy → embed → indexAdd → ObjectBox |
| `RagManager.java` | Package fixed `android.rag` → `android.app.rag`, `isIndexed()` added, `requireService()` pattern |
| `IRagService.aidl` | Package fixed to `android.app.rag`, `isIndexed()` added |
| `RagService.java` | `isIndexed()` implemented in mBinder stub with real ObjectBox query |

All committed and pushed to `origin/lineage-23.0`.

---

## What comes next

### Most pressing — naming convention decision (Phase 4 blocker)
Before implementing `ToolScanner`, the protocol naming needs a decision:
- `ai.jarvisos.tool.*` prefix in meta-data? (OS-specific, simple)
- `res/xml/jarvis_tools.xml` file inside APK? (cleaner, no OS prefix in app code)
- `JarvisToolProvider` ContentProvider? (most powerful, most work for app devs)
Discuss with Sam before writing any code.

### Phase 3 (still pending)

### Priority 1 — Full file review (Kevin's request)
Go through EVERY Java file in:
```
\\wsl.localhost\Ubuntu-24.04\home\kevin\android\lineage\frameworks\base\services\core\java\com\android\server\rag\
```
22 files total. Check for:
- Stale TODOs that are now implemented
- Import consistency (`android.app.rag` vs `android.rag`)
- Any leftover conflict markers
- Anything that doesn't compile logically

### Priority 2 — Cactus exploration
Kevin has only explored `graph/` in the Cactus source. He wants to understand the rest before wiring handles into RagService.

Cactus fork lives at:
```
\\wsl.localhost\Ubuntu-24.04\home\kevin\android\lineage\vendor\cactus\
```
Unexplored folders:
- `engine/`  — the core inference loop
- `kernel/`  — low-level compute kernels (SIMD/NEON)
- `ffi/`     — C FFI layer that our JNI calls into
- `models/`  — model loading/quantization logic

Approach: read each folder's key headers, explain what they do, then connect to how `CactusWrapper.java` calls them. Kevin learns best with code in front of him — use Explanation mode here.

### Priority 3 — Build verification
```bash
cd ~/android/lineage
source build/envsetup.sh
lunch lineage_<device>-userdebug
m libcactus
```
Note: Kevin has never successfully run `m libcactus` yet. The Android.bp at `vendor/cactus/Android.bp` was written but never compiled. Expect errors — work through them.

Also still TODO:
```makefile
# Wire jarvisos.mk into device product config:
$(call inherit-product, vendor/jarvisos/products/jarvisos.mk)
```

---

## Key architectural facts to keep in mind

- **ObjectBox owns metadata. Cactus owns vectors.** Never store `float[]` in ObjectBox entities.
- **`DocumentChunk.cactusIndexId`** is the bridge — it's an int ID into Cactus's binary `index.bin`
- **Two-stage retrieval**: ObjectBox narrows (free) → Cactus re-ranks semantically (bounded cost)
- **`system_server` process** — memory leaks crash the whole phone. Always release JNI resources.
- **No Kotlin in system_server** — CactusWrapper uses static JNI methods (`jclass` not `jobject`)
- **`cat > file << 'EOF'`** is the reliable way to write files in WSL when git conflict markers interfere

## Package map (important — was wrong before, now fixed)
```
android.app.rag          — public API layer (RagManager, IRagService)
com.android.server.rag   — system service implementation (RagService, all workers)
```

---

## File paths cheat sheet

```
# Source
~/android/lineage/frameworks/base/services/core/java/com/android/server/rag/   ← all 22 Java files
~/android/lineage/vendor/cactus/                                                 ← Cactus fork
~/android/lineage/vendor/cactus/android/cactus_jni.cpp                          ← JNI bindings (ours at bottom)
~/android/lineage/vendor/cactus/Android.bp                                       ← Soong build
~/android/lineage/vendor/jarvisos/products/jarvisos.mk                           ← product makefile
~/android/lineage/.repo/local_manifests/jarvos.xml                              ← repo manifest

# Docs / memory
~/android/lineage/vendor/jarvisos/AGENTS.md                                      ← project spec
~/android/lineage/vendor/jarvisos/HANDOFF.md                                     ← this file
~/learning/CURRICULUM.md                                                          ← learning tracker
~/learning/java/PROGRESS.md                                                       ← quiz history
~/learning/java/effective-java/ch02-creating-and-destroying-objects/NOTES.md     ← chapter notes

# Skills (gitignored)
~/android/lineage/vendor/jarvisos/skills/TEACHING_MODE.md
~/android/lineage/vendor/jarvisos/skills/CODE_SPRINT_MODE.md
~/android/lineage/vendor/jarvisos/skills/EXPLANATION_MODE.md
```

---

## Kevin's working style (important)
- Prefers **chunk-by-chunk** — never dump everything at once
- Wants to **understand before implementing** — read code first, then build
- Memory is self-described as bad — always recap what we're doing and why
- Likes competitive/algorithmic framing — "why is this approach better than X"
- Clean senior-engineer style code — no comments unless they add real value
- Will say "we good?" to confirm before moving on — wait for that

---

## Git state at end of Session 5
```
frameworks/base:  lineage-23.0  →  origin/lineage-23.0  (ahead 0, clean)
vendor/jarvisos:  main          →  JarvisOs/main
vendor/cactus:    main          →  JarvisOs/main
```
lineage-21.0 branch deleted (it was the wrong branch from a misconfiguration).
