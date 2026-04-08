# JarvisOS Handoff — Session 11 (Apr 2026)

## Code state

| Repo | Branch | Last commit | Status |
|------|--------|-------------|--------|
| frameworks/base | lineage-21.0 | 1b04e21 | ✅ Phase 6 pushed |
| vendor/jarvisos | main | 3268457 | ✅ clean |
| vendor/cactus | main | a52d69f | ✅ JNI fix pushed |

---

## What was built this session

### Phase 5 — Agentic loop (complete)
- `agent/JarvisExecutor.java` — stateful loop (Plan→Retrieve→Tool→Respond, max 5 turns)
- `agent/RouterNode.java` — deterministic `<|tool_call|>` token check, no model call
- `agent/PlanNode.java`, `RetrieveNode.java`, `ToolNode.java`, `RespondNode.java`
- `model/AgentSession.java` + `AgentTurn.java` — ObjectBox entities, persisted after every node
- `ToolDispatcher.dispatchByName()` — named tool dispatch for ToolNode
- `JarvisService.processQuery()` → routes through JarvisExecutor

### Phase 6 — Memory, multimodal, sub-agents (complete)
- `agent/DreamWorker.java` — nightly WorkManager (24h, charging). Merges AgentTurns → UserContext.facts
- `agent/SubAgentExecutor.java` — child loop for sub-tasks (maxTurns=2, inherits parent context)
- `IJarvisService.aidl` — `processQueryWithImage()` + `processQueryWithAudio()` added to both copies
- `JarvisService` — implements both multimodal methods (text fallback until Cactus Gemma 4 pull)
- `JarvisManager` — `queryWithImage()` + `queryWithAudio()` public wrappers
- `UserContext` — `facts` (JSON array, cap 50) + `consolidatedAt` fields
- `AgentSession` — `consolidated` flag for DreamWorker gate

### Cactus JNI fix
All 10 JNI bindings in `cactus_jni.cpp` still referenced `com.android.server.rag` —
fixed to `com.android.server.jarvis.inference`. Would have caused `UnsatisfiedLinkError` at runtime.

---

## What to do next (in order)

- [ ] **Sam: pull Gemma 4 into vendor/cactus**
  Pull upstream llama.cpp Gemma 4 support. Key things to land:
  `GEMMA4 = 15` in ModelType, `ToolCallInfo` struct, `gemma4/` model sources, Android.bp updated.
  Confirm `<|tool_call|>` token appears in CactusWrapper.complete() output with a Gemma 4 GGUF.
  Fill in the `TODO` blocks in `JarvisService.processQueryWithImage/Audio()`.

- [ ] **Register "primary" ModelRegistry entry in JarvisService**
  Once Gemma 4 GGUF path is known, add:
  ```java
  registry.register("primary", GEMMA4_MODEL_PATH, INDEX_DIR_RAG, EMBED_DIM);
  ```
  PlanNode + RespondNode + DreamWorker already prefer "primary", fall back to "rag".

- [ ] **lineage-22.2 re-sync** (do at uni, fast connection, ~3hrs, run in tmux)
  Nothing Phone 2 (Pong) target requires 22.2. All JarvisOS code is pure Java — no conflicts expected.

- [ ] **On-device test on ARM hardware**
  x86_64 emulators cannot load Cactus. Minimum: Android 14 ARM device.
  Test path: boot → JarvisService starts → CactusWrapper.init() succeeds →
  processQuery() runs JarvisExecutor → RouterNode routes correctly.

---

## Key facts (carry forward)

- Cactus JNI: `Java_com_android_server_jarvis_inference_CactusWrapper_*`
- JarvisExecutor is the entry point for all queries — JarvisService no longer dispatches directly
- RouterNode is deterministic, no model call — `<|tool_call|>` token boundary check only
- maxTurns = 5 (parent sessions), 2 (sub-agent sessions) — do NOT raise without hardware profiling
- DreamWorker runs nightly, charging only — first run consolidates any sessions from testing
- Multimodal API is wired end-to-end; CactusWrapper calls are the only missing piece
- No Kotlin in system_server
- x86_64 emulators cannot load Cactus — ARM hardware only
