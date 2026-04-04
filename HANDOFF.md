# JarvisOS Handoff — Session 8 (Apr 4 2026)

## Build environment note

lineage-22.2 re-sync still pending — do at uni on fast connection.
Decision: code Phase 4 now, rebase on 22.2 after. Same pattern as the 23→21 migration.
All Phase 4 code is pure Java in frameworks/base — no device-specific dependencies.

---

## Code state — ALL SAFE, READY TO PUSH

| Repo | Branch | Status |
|------|--------|--------|
| frameworks/base | lineage-21.0 | ✅ Phase 4 tools written, needs push |
| vendor/jarvisos | main | ✅ MDs pushed (docs session) |
| vendor/cactus | main | ✅ clean |

---

## What was done this session (Session 8)

### MD cleanup
- `pixel6.xml` + `roomservice.xml` — commented out (leftover manifests)
- `pong.xml` — updated with commented-out Pong entry + instructions
- Migrated all MDs from `~/vendor_jarvisos/` → `vendor/jarvisos/` (single source of truth)
- Added `AGENTIC_LOOP.md` — full Phase 5 architecture spec
- `.gitignore` updated to track MDs — pushed so Sam can read them

### Phase 4 — Tool Registry (IN PROGRESS)

#### Files written
- `tools/AppRecord.java` — NEW. One ObjectBox entity per installed app.
  `ToMany<ToolRecord>` relation. Fields: packageName, appLabel, sourceType, lastScanTime, isActive.
- `tools/ToolRecord.java` — NEW. One ObjectBox entity per tool.
  Replaces flat ToolDefinition. Has `ToOne<AppRecord>` back-link, `rawDefinition` field
  (tool name + description + params — the string that gets embedded).
  `cactusIndexId` points into Cactus binary index.
- `tools/ToolScannerService.java` — REWRITTEN.
  Package fixed: `com.android.server.rag` → `com.android.server.rag.tools`.
  Now uses AppRecord + ToolRecord split.
  `buildRawDefinition()` constructs richer embedding string.
  `resolveAppLabel()` pulls human-readable app name from PackageManager.
- `tools/ToolDispatcher.java` — NEW. Resolves + executes tools.
  Semantic search (embed query → HNSW on ToolRecord) → metadata fallback.
  Builds OpenAI-compatible toolsJson → CactusWrapper.complete() selects tool.
  Fires broadcast Intent to app's BroadcastReceiver.
  ResultReceiver + CountDownLatch pattern — blocking, 10s timeout.
- `tools/ToolDefinition.java` — TOMBSTONED. Replaced by AppRecord + ToolRecord.
- `RagService.java` — UPDATED. Added ToolDispatcher field + instantiation.
  processQuery() now tries tool path first (returns null = no match → falls through to RAG).

#### Key architectural decision
AppRecord/ToolRecord two-entity split chosen over flat ToolDefinition because:
- Phase 5 ToolNode needs to know which *app* owns a matched tool for dispatch
- `ToOne<AppRecord>` gives ToolDispatcher the packageName + receiverClass cleanly
- Matches AGENTS.md spec exactly

---

## Next — complete Phase 4

- [ ] `IToolRegistry.aidl` — public AIDL: `listTools()`, `getTool(id)`, `searchTools(query)`
- [ ] Wire `IToolRegistry` into `IRagService` or as a separate published service
- [ ] Add AppRecord_ + ToolRecord_ generated ObjectBox query classes to Android.bp
- [ ] Delete `ToolDefinition.java` tombstone (after confirming no references remain)
- [ ] Commit + push frameworks/base

## After Phase 4 is complete
- lineage-22.2 re-sync at uni
- Rebase frameworks/base lineage-21.0 branch onto lineage-22.2
- Phase 5: JarvisExecutor agentic loop (see AGENTIC_LOOP.md)

---

## Key facts to remember

- ToolDispatcher.resolveAndDispatch() returns null if no tool matches — RagService falls through to RAG path
- Tool broadcast timeout: 10 seconds (DISPATCH_TIMEOUT_MS)
- rawDefinition = toolName (spaces) + description + paramsJson — richer than description alone
- "rag" and "tools" model handles both point to the same .gguf file, separate index dirs
- No Kotlin in system_server
- ToolDefinition.java is a tombstone — do not reference it
