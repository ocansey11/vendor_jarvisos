# JarvisOS Handoff — Session 11 (Apr 2026)

## Code state

| Repo | Branch | Status |
|------|--------|--------|
| frameworks/base | lineage-21.0 | ✅ Phase 5 committed + pushed (f43771b) |
| vendor/jarvisos | main | 🔄 MDs updated this session — not yet committed |
| vendor/cactus | main | ✅ JNI fix committed + pushed (a52d69f) |

---

## This session — Phase 5 agentic loop + Cactus JNI fix

### Critical bug fixed
All 10 JNI bindings in `vendor/cactus/android/cactus_jni.cpp` still referenced
`com.android.server.rag.CactusWrapper`. After the package rename these caused
`UnsatisfiedLinkError` at runtime — Cactus would fail to load. Fixed to
`com.android.server.jarvis.inference.CactusWrapper`.

### Phase 5 written
```
frameworks/base/services/core/java/com/android/server/jarvis/
│
├── agent/                     ← NEW
│   ├── JarvisExecutor.java    ← loop runner (Plan→Retrieve→Tool→Respond, max 5 turns)
│   ├── RouterNode.java        ← deterministic routing: <|tool_call|> token check
│   ├── PlanNode.java          ← first-turn plan generation via Gemma 4 / "rag" fallback
│   ├── RetrieveNode.java      ← Stage 1 MetadataSearch + Stage 2 HNSW retrieval
│   ├── ToolNode.java          ← parses Gemma 4 tool call JSON → dispatchByName()
│   └── RespondNode.java       ← final answer via CactusWrapper.complete()
│
└── model/                     ← UPDATED
    ├── AgentSession.java      ← ObjectBox entity: persisted loop state
    ├── AgentSession_.java     ← ObjectBox stub
    ├── AgentTurn.java         ← ObjectBox entity: one turn in a session
    └── AgentTurn_.java        ← ObjectBox stub
```

Also:
- `ToolDispatcher.dispatchByName(toolName, argsJson)` — bypasses semantic search, used by ToolNode
- `JarvisService.processQuery()` → now routes through `JarvisExecutor.execute(query)`
- `MyObjectBox` registers AgentSession + AgentTurn

---

## What Claude Code must do next (in order)

- [ ] **Sam: Cactus upstream pull**
  Pull upstream llama.cpp Gemma 4 support into `vendor/cactus`.
  Target commits: `GEMMA4 = 15` in ModelType, `ToolCallInfo` struct, `gemma4/` model dir.
  Confirm `CactusWrapper.complete()` outputs `<|tool_call|>` token with a Gemma 4 GGUF.
  Until then: Phase 5 loop compiles and runs, but falls back to the "rag" model entry
  which may not emit Gemma 4 tool tokens.

- [ ] **Register "primary" ModelRegistry entry**
  In `JarvisService.initializeAsync()`, add:
  ```java
  ModelRegistry.ModelEntry primary = registry.register("primary", GEMMA4_MODEL_PATH,
      INDEX_DIR_RAG, EMBED_DIM);
  ```
  `PlanNode` and `RespondNode` already prefer "primary" and fall back to "rag".
  Add `GEMMA4_MODEL_PATH` constant once the Gemma 4 GGUF path is known.

- [ ] **Wire Android.bp for agent/ package**
  The `agent/` directory isn't explicitly listed in Android.bp — Soong should pick it up
  via `java_library_static` glob, but verify after adding the new package.

- [ ] **Commit vendor/jarvisos MDs** (AGENTS.md, HANDOFF.md updated this session)

---

## Key facts (carry forward)

- Cactus JNI bindings: `Java_com_android_server_jarvis_inference_CactusWrapper_*`
- JarvisExecutor replaces the old direct tool/RAG dispatch in processQuery()
- RouterNode is deterministic — no model call. Token check only.
- maxTurns = 5. Hard ceiling. Don't raise without profiling on target hardware.
- AgentSession/AgentTurn persisted after every node — crash-resumable
- DreamWorker (Phase 6) will consume AgentTurn history → UserContext facts
- lineage-22.2 re-sync still pending (do at uni, fast connection, ~3hrs, run in tmux)
- No Kotlin in system_server
- x86_64 emulators cannot load Cactus — ARM hardware only for native testing
