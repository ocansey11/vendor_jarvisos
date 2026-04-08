# JarvisOS Handoff — Session 10 (Apr 2026)

## Code state

| Repo | Branch | Status |
|------|--------|--------|
| frameworks/base | lineage-21.0 | 🔄 Clean jarvis/ — not yet committed |
| vendor/jarvisos | main | 🔄 MDs updated this session |
| vendor/cactus | main | ✅ clean |

---

## This session — rag/ removal + doc cleanup

Deleted both old `rag/` folders. Package rename is now fully resolved on disk.
Updated all MDs to reference `android.jarvis` / `com.android.server.jarvis` only.

### What was done

- Deleted `frameworks/base/services/core/java/com/android/server/rag/` (old server package)
- Deleted `frameworks/base/core/java/android/rag/` (old public API package)
- Updated `CLAUDE.md` layout — now shows `server/jarvis/` and `android/jarvis/` correctly
- Updated `AGENTS.md` — Layer 2 marked ✅, Known Gaps cleaned, session log updated
- Updated `TASK_1.md` — corrected package path, marked done (Phase 3 complete)

### Current disk state (clean)

```
frameworks/base/core/java/android/jarvis/         ← public API (package android.jarvis)
    IRagService.aidl, IToolRegistry.aidl, RagManager.java, RagException.java

frameworks/base/services/core/java/com/android/server/jarvis/   ← system service
    RagService.java, Android.bp, IRagService.aidl
    core/, inference/, indexing/, search/, model/, tools/
```

---

## What Claude Code must do next (in order)

- [ ] **Wire ObjectBox annotation processor into Android.bp**
  `AppRecord_`, `ToolRecord_`, `SourceFile_`, `DocumentChunk_` are generated classes.
  Add `objectbox-processor` as a `java_plugin` once the JAR lands in
  `vendor/jarvisos/prebuilts/objectbox/`.
  Until then: document the build gap in Android.bp as a TODO comment.

- [ ] **Add IToolRegistry.aidl wiring**
  `RagService.java` publishes `"jarvis_tools"` via `ServiceManager.addService()`.
  Verify the `IToolRegistry.aidl` stub is imported correctly in `RagService.java`.

- [ ] **Commit**
  Suggested message: `refactor: remove rag/ package, jarvis/ is now the only location`

---

## Key facts (carry forward)

- Service name strings `"rag"` and `"jarvis_tools"` in RagService are runtime strings — unchanged
- IToolRegistry published as `"jarvis_tools"` — separate from `"rag"` service
- ToolDispatcher.resolveAndDispatch() returns null on no match — falls through to RAG
- Tool broadcast timeout: 10 seconds
- lineage-22.2 re-sync still pending (do at uni, fast connection, ~3hrs, run in tmux)
- No Kotlin in system_server
- x86_64 emulators cannot load Cactus — ARM hardware only for native testing
