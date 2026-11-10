# The AX ERD against the code (10 Nov 2026)

The code does not yet match the ERD draft. Ten points disagree; three answer
open questions in part. Nothing below has been changed in the code or in the
ERD. Kevin decides which side moves.

Service code: `frameworks/base/services/jarvis/java/com/android/server/jarvis/`
(below, `jarvis/`). Apps and tool definitions: `vendor/jarvisos/`.

## Disagreements with the skill text

Items 1 and 2 are corrected in this repo copy of the skill (10 Nov 2026).
Kevin's account copy still has the old text.

1. **The inference boundary is `Inference`, not `CactusWrapper`.** Since
   October there are two engines behind `jarvis/inference/Inference.java` and
   `InferenceEngine`: Cactus (`CactusEngine` + `CactusWrapper`) and llama.cpp
   (`LlamaEngine`). `persist.sys.jarvis.engine` picks one. `CactusWrapper` is
   one engine's JNI, not the swappable boundary.
2. **Cactus does load on x86_64.** Since 22 Oct, libcactus builds for the
   emulator through a SIMDe shim (`vendor/cactus`, branch `jarvis-v2.2.2`),
   and the torch turned on through Cactus on the emulator (roadblock 50).
   `auto` still never picks Cactus on x86_64; it runs only when asked for by
   name. llama.cpp is the engine the emulator uses by default.
3. **The name.** `AGENT_EXPERIENCE.md` calls AX "Agent Experience"; this
   skill calls it "Agentic Xperience". Pick one.

## Entity by entity

| ERD | In the code | Match |
|---|---|---|
| `MODEL` | `core/ModelRegistry` entries, in memory only. Roles: `rag`, `tools` (embedding), `primary` (chat), plus Needle (tool calling) and Whistle (speech) in `inference/Needle`. | Partial: not persisted; roles differ from `router\|rag\|asr\|tts` |
| `MODEL_INDEX` | Index handles in `ModelRegistry`; files in `/data/system/jarvis/index_{rag,tools}_<engine>/` and `index_tools_needle/`. llama.cpp uses `inference/FlatVectorIndex` (vectors in a file). | Partial: one index set per engine |
| `SESSION` | Two kinds. `model/AgentSession` (one run of the agent loop, DreamWorker input) and AX app sessions (`session/SessionManager`, in memory; History writes each as a `Conversation` with `mode = "session"`). | Partial: no `originPackage` stored |
| `TURN` | `model/AgentTurn` (`role`, `content`, `toolName`, `toolArgs`) and `model/Message` for History. | Partial: no `tokenCount` |
| `INTENT` | No entity. Nearest: the Needle fast path's confidence (`tools/FastPath`, threshold `persist.sys.jarvis.fast_confidence`) and `agent/PlanNode`. | Missing |
| `TOOL` | `tools/ToolRecord`: `toolName`, `description`, `paramsJson`, `rawDefinition`, `receiverClass`, `cactusIndexId`, `requiresConfirmation`, FK to `AppRecord`. | Partial: see open questions 1 and 2 |
| `TOOL_PROVIDER` | `tools/AppRecord`: `packageName`, `appLabel`, `sourceType`, `isActive`. No `uid`, no `aidlInterface`. | Partial: transport differs (below) |
| `GRANT` | No entity. Nearest: a session's `closedTools` and the tool fence (`ToolDispatcher.Fence`), plus the signature permission on `IJarvisService`. | Missing |
| `TOOL_CALL` | No entity. Recorded as an `AgentTurn` (`toolName`, `toolArgs`), as a History `Message` with `source = "tool"`, and in the in-memory request trace (`getStatus`, `jh trace`). | Missing as an entity |
| `TOOL_RESULT` | No entity. History keeps `"name(args) → result"` cut to 600 characters (`History.CONTENT_CHARS`); `AgentSession.lastToolResult` keeps the last one. | Missing |
| `AUDIT_EVENT` | No entity. Fence refusals and confirmation stops go to logcat only. | Missing |
| `MEMORY_ITEM` | `model/SourceFile` + `DocumentChunk` (pointers into the RAG index) and `model/UserContext` (facts from DreamWorker). | Partial |

The ObjectBox rule holds: no entity stores an embedding. Vectors live in the
engine's index files; entities hold `cactusIndexId` pointers.

## Transport: not a per-provider AIDL interface

Jarvis's own services are AIDL (`jarvis`, `jarvis_tools`, `jarvis_session`).
A call to an app's tool is not. `ToolDispatcher.dispatch` sends a broadcast
to the tool's `receiverClass` with a `ResultReceiver` and waits up to
`DISPATCH_TIMEOUT_MS`. Tools with a `receiverClass` of `@system/...` run in
`system_server` through `SystemToolExecutor`, with no broadcast. Binder
carries both, so the security argument for Binder still holds, but the ERD's
`TOOL_PROVIDER.aidlInterface` describes a design the code does not use. The
code's equivalent is `TOOL.receiverClass`.

## What the code says about the open questions

1. **Tool ownership: both.** Apps register their own tools (a receiver for
   the intent `com.jarvisos.TOOL` with meta-data `com.jarvisos.tool.name`,
   `.description` and `.input_schema`, read by `ToolScannerService`), and Jarvis adds
   built-ins (`vendor/jarvisos/tools/*.json`, `@system/` receivers). A tool is
   looked up by `(toolName, receiverClass)`, so in effect the key is
   `(package, name)`. During a session the fence scopes names to one package.
2. **`sideEffect` placement: on `TOOL`, as a boolean.** `ToolRecord.requiresConfirmation`
   (JSON `requires_confirmation`; today only `make_phone_call` sets it, so
   `send_sms` runs without asking) is a two-level version of `sideEffect`. The ask-first flow is a stub: the
   dispatcher returns `CONFIRM_REQUIRED` text and does not run the tool
   ("Phase 7: persist pendingToolCall" in `ToolRecord` and `ToolDispatcher`).
3. **`GRANT` granularity:** nothing in the code yet.
4. **Router vs RAG:** no `INTENT` is stored. The Needle fast path decides
   first; if it is not confident, the agent loop (`agent/RouterNode`,
   `PlanNode`) decides again.
5. **Result persistence:** History already stores a 600-character summary,
   not the full payload, and deletes it after 24 h. No hash is stored.

## Constraint not in the code

TASK_2 (inference and agent logic out of `system_server`) is not described
anywhere in `vendor/jarvisos`. Today all of it runs in `system_server`.
