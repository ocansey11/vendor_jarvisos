# AGENTIC_LOOP.md — JarvisOS Agent Orchestration Architecture
> Research + design spec for Phase 5. Updated April 2026 post-Gemma 4 drop.
> Decisions below are backed by research — sources are inline so the team can audit them.

---

## The Gap We're Filling

Cactus is to JarvisOS what Ollama is to LangGraph — it's the inference engine, not the orchestrator.
Right now JarvisOS can: embed, retrieve, complete. One shot. No loop.

What we're missing is the **agentic loop** — the layer that:
- Maintains state across multiple Cactus calls
- Decides what tool to call next based on what came back
- Retries, branches, and knows when it's done
- Survives interruption and resumes

LangGraph solves this in Python. Claude Code's leaked source solves this in TypeScript.
JarvisOS needs to solve this in Java, inside `system_server`, with no JVM heap budget to waste.

---

## What We Learned From the Claude Code Leak

Claude Code leaked March 31 2026 from Anthropic's npm registry via an exposed sourcemap.
Source: https://github.com/nirholas/claude-code | https://dev.to/varshithvhegde/the-great-claude-code-leak-of-2026

**QueryEngine.ts** — the core agentic loop:
- Streaming responses from the LLM
- Tool-call loop: model returns a tool_use block → execute tool → feed result back → repeat
- Loop exits when model returns a text block with no tool calls (done) or hits max iterations
- **Strict Write Discipline: state only updates after confirmed successful action** ← steal this

**coordinator/** — multi-agent orchestration (Swarm pattern):
- Sub-agents spawned via AgentTool, each with its own tool set and context window
- Coordinator routes tasks to the right sub-agent
- Relevant for Phase 6+ when JarvisOS spawns specialised sub-agents

**memdir/** + **tasks/** + **state/** — the three-layer memory:
- `tasks/` = in-flight work (what the agent is currently doing)
- `memdir/` = persistent memory across sessions (what it has learned)
- `state/` = current execution snapshot (resumable)
- JarvisOS maps these to: `AgentSession` (tasks) + `UserContext` (memdir) + ObjectBox persistence (state)

**KAIROS flag** — unreleased autonomous background daemon:
- autoDream: nightly memory consolidation
- Merges observations, removes contradictions, converts vague insights to verified facts
- This becomes Phase 6's DreamWorker. Don't build it in Phase 5.

**Key principle stolen directly:**
> The agent treats its own memory as a "hint" and verifies against ground truth before acting.
> It never trusts stored state blindly.

---

## LangGraph Validation

LangGraph's internals were audited to validate the JarvisOS design.
Source: https://langchain.com/langgraph | https://deepwiki.com/langchain-ai/langgraph/4.1-checkpointing-architecture

**Result: the AgentSession + RouterNode + node pipeline design is correct.**

| LangGraph concept | What it is | JarvisOS equivalent |
|---|---|---|
| **State** | Typed shared dict flowing through the graph | `AgentSession` (ObjectBox entity) |
| **Node** | Function: reads state → does work → returns state update | `PlanNode`, `RetrieveNode`, `ToolNode`, `RespondNode` |
| **Conditional Edge** | Routing function: reads state, returns name of next node | `RouterNode.route()` — deterministic, no model call |
| **Checkpoint** | State snapshot after every node, keyed by thread_id | ObjectBox write after every node, keyed by `sessionId` UUID |

LangGraph does NOT do its own tool-call parsing. It delegates to the model's native tool interface, then
uses a `ToolNode` that intercepts the tool_call response and executes it. Same pattern we're building.

LangGraph supports different models per node with no special API — each node calls whatever model it wants.
This is how we'll eventually do planning (small model) vs. responding (larger model) if needed.

---

## Why Gemma 4 Changes the Plan

Gemma 4 dropped April 2, 2026. It changes two things: the model strategy and the router implementation.
Sources:
- https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/
- https://huggingface.co/blog/gemma4
- https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4
- https://newsroom.arm.com/blog/gemma-4-on-arm-optimized-on-device-ai

### What Gemma 4 is

Four variants, two relevant for JarvisOS:

| Model | Architecture | Active Params | Modalities | Context |
|-------|-------------|---------------|------------|---------|
| **E2B** | MoE | ~2B active | Text + Image + Video + Audio | 128K |
| **E4B** | MoE | ~4B active | Text + Image + Video + Audio | 128K |
| 26B A4B | MoE | 4B active | Text + Image only | 256K |
| 31B Dense | Dense | 31B | Text + Image only | 256K |

E2B and E4B are the mobile targets. 26B/31B are desktop-only.

### Native tool calling — why this matters

Gemma 4 has **tool calling baked into the weights** via dedicated special tokens, not prompt engineering.
The model was trained on tool-call tasks end-to-end. Token lifecycle:

```
<|tool_call|>
{"name": "check_visa_status", "arguments": {"passport_number": "GH123456"}}
<|end_tool_call|>
```

Tool definitions use OpenAI-compatible JSON schema — the same format `ToolDispatcher` already builds.
This means `RouterNode.isToolCall()` becomes a token boundary check, not string heuristics.
The model doesn't need to be "guided" into calling tools — it just does it when appropriate.

Previously: FunctionGemma (270M, zero-shot only, no system prompts, unreliable on complex queries).
Now: Gemma 4 E2B, trained natively on tool calling, multimodal, 64× larger active param count.

### ARM performance on target hardware

Arm Newsroom reported 5.5× prefill speedup and 1.6× faster decode on Armv9 CPUs for E2B.
Google co-optimised with Qualcomm and MediaTek at launch.
E2B runs on a Raspberry Pi 5 ($35 device). The Nothing Phone 2 (Snapdragon 8+ Gen 1, Armv9) will run it.

### Cactus compatibility

Gemma 4 support merged into llama.cpp on April 2, 2026 (same day as the model release).
Text + image inference is stable. Audio support is in progress (llama.cpp issue #21325).
Source: https://avenchat.com/blog/run-gemma-4-with-llama-cpp

**Sam's job before Phase 5 coding starts:** pull upstream llama.cpp into Cactus, confirm
Gemma 4 GGUF loads and completes. Q4_K_M and UD-Q4_K_XL quantised weights are on HuggingFace.
This is the prerequisite gate for everything below.

---

## Mobile Hardware Constraints

This section exists because mobile is not a server. Every architectural decision below is shaped by it.
Source: https://v-chandra.github.io/on-device-llms/ | https://www.mdpi.com/2227-7390/13/22/3689

### RAM budget

```
Runtime RAM ≈ model file size × 1.5
(the 0.5× overhead = KV cache + activations)
```

| Model | Q4 disk size | Runtime RAM | Fits on 8GB phone? |
|-------|-------------|-------------|---------------------|
| E2B Q4_K_M | ~1.5GB | ~2.2GB | ✅ comfortable |
| E4B Q4_K_M | ~2.5GB | ~3.8GB | ✅ tight but OK |
| 26B Q4 | ~14GB | ~21GB | ❌ no |

**E4B is the ceiling for the Nothing Phone 2 (8GB RAM, ~4GB usable for inference).**
E2B is the safer default — leaves room for the OS and system_server's own heap.

### KV cache management

Default f16 KV cache. Switching to q4_0 KV cache gives ~3× faster decode with minimal quality loss.
In a 5-turn agentic loop, the KV cache grows each turn as context accumulates.
`maxTurns = 5` directly bounds KV cache growth. Do not raise this without profiling.

Truncation strategy for `accumulatedContext`: keep the original query + last N tool results.
Drop middle turns if context window pressure is hit. Recency > completeness for tool results.

### Thermal ceiling

Sustained inference causes thermal throttling within 3–5 minutes on Snapdragon SoCs.
Mobile memory bandwidth (50–90 GB/s) vs server GPU (2–3 TB/s) = every token costs more.

Implications that are already baked into the design:
- `maxTurns = 5` hard ceiling ✅
- No background polling ✅
- WorkManager charging-only for JarvisIndexWorker ✅
- Agentic loop only on user-initiated queries ✅

### Single model is non-negotiable on mobile

Two models loaded simultaneously = double the RAM. Not viable on most phones.
One model handles everything: plan, route, tool selection, respond.
Different prompts, same weights. This is the correct mobile-first pattern.

---

## Model Registry Update for Phase 5

The current `ModelRegistry` has two entries: `"rag"` (document index) and `"tools"` (tool index).
This split made sense when we had separate specialist models. With Gemma 4 as the single model,
the architecture simplifies:

```
"primary"   → Gemma 4 E2B/E4B — handles plan, tool selection, RAG completion, respond
"rag"       → keep as index handle key (the index dir and HNSW data don't change)
"tools"     → keep as index handle key (same reason)
```

The model handle is the same for all three. Only the index handles differ.
Phase 5 task: update `ModelRegistry.register()` calls in `JarvisService` to register
Gemma 4 as `"primary"`, then have PlanNode/RespondNode pull `"primary"`.

---

## JarvisOS Agent Architecture — Phase 5

### Prerequisite: Cactus upstream pull (Sam)

Before any Phase 5 code is written:
1. Sam pulls upstream llama.cpp into `vendor/cactus`
2. Confirm Gemma 4 GGUF loads via `CactusWrapper.init()`
3. Confirm `CactusWrapper.complete()` returns `<|tool_call|>` tokens in output
4. Confirm embed still works (needed for RAG path)
5. Update `CactusWrapper` JNI bindings if the llama.cpp API changed

### The core loop

```
User query (text or voice)
    |
    v
AgentSession created (ObjectBox, persisted immediately)
    |
    v
┌─────────────────────────────────────────────────────────┐
│                    JarvisExecutor                        │
│                    (the agentic loop)                    │
│                                                          │
│  PLAN node      — Gemma 4: what does this query need?   │
│       |                                                  │
│  RETRIEVE node  — RAG lookup via JarvisService Binder   │
│       |                                                  │
│  TOOL node      — parse <|tool_call|>, fire broadcast   │
│       |                                                  │
│  RESPOND node   — Gemma 4: generate final response      │
│       |                                                  │
│  ROUTER         — token check: tool_call? done? failed? │
│       |                                                  │
│  (repeat until ROUTER says DONE or maxTurns hit)        │
└─────────────────────────────────────────────────────────┘
    |
    v
Response returned via Binder to caller
AgentSession updated in ObjectBox (becomes long-term memory)
```

### AgentSession — the state object

This is the LangGraph State equivalent. Everything the loop needs lives here.
Persisted to ObjectBox after every node — survives system_server crash.

```java
@Entity
public class AgentSession {
    @Id long id;
    String sessionId;           // UUID — the LangGraph "thread_id" equivalent
    String originalQuery;       // what the user asked (never mutated)
    String currentPlan;         // what PlanNode decided to do this turn
    String lastToolResult;      // raw result from last ToolNode execution
    String accumulatedContext;  // retrieved docs + tool results so far
                                // truncate oldest entries if context pressure hits
    int turnCount;              // incremented by Router after each full turn
    int maxTurns;               // hard ceiling — default 5, never raise without profiling
    String status;              // PLANNING | RETRIEVING | TOOL_CALL | RESPONDING | DONE | FAILED
    long createdAt;
    long lastUpdatedAt;
    ToMany<AgentTurn> turns;    // full turn history (used by DreamWorker in Phase 6)
}

@Entity
public class AgentTurn {
    @Id long id;
    String role;                // "model" | "tool" | "user"
    String content;             // what was said/returned
    String toolName;            // populated if role == "tool"
    String toolArgs;            // JSON args that were passed
    long timestamp;
    ToOne<AgentSession> session;
}
```

### RouterNode — updated for Gemma 4 native tool tokens

The original design used string heuristics. With Gemma 4, `<|tool_call|>` is a hard token boundary.
`isToolCall()` becomes a simple token check rather than fuzzy pattern matching.

```java
public class RouterNode {

    // Gemma 4 tool call token — present in output when model wants to call a tool.
    // Source: https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4
    private static final String TOOL_CALL_TOKEN = "<|tool_call|>";

    public enum Next {
        RETRIEVE,    // model explicitly asked for more context
        TOOL_CALL,   // model emitted a tool call token
        RESPOND,     // no tool call, model is ready to generate final answer
        DONE,        // response node completed successfully
        FAILED       // error or maxTurns hit
    }

    public Next route(AgentSession session, String modelOutput) {
        if (session.turnCount >= session.maxTurns) return Next.FAILED;
        if (isToolCall(modelOutput))               return Next.TOOL_CALL;
        if (needsMoreContext(modelOutput, session)) return Next.RETRIEVE;
        return Next.DONE;
    }

    private boolean isToolCall(String output) {
        // Deterministic token check — no LLM needed, no heuristics.
        return output != null && output.contains(TOOL_CALL_TOKEN);
    }

    private boolean needsMoreContext(String output, AgentSession session) {
        // Model indicates it lacks context. Check for known retrieval intent signals.
        // Keep this list short — don't add heuristics without measuring false positive rate.
        return output != null
            && session.accumulatedContext == null
            && (output.contains("I don't have information")
                || output.contains("let me look that up")
                || output.contains("I need to retrieve"));
    }
}
```

### ToolNode — Gemma 4 tool call parsing

Gemma 4 outputs tool calls as JSON between `<|tool_call|>` and `<|end_tool_call|>` tokens.
ToolNode parses this, maps to a registered tool, and fires via ToolDispatcher.

```java
// Inside ToolNode.execute(AgentSession session, String modelOutput):

// 1. Parse tool call from model output
int start = modelOutput.indexOf("<|tool_call|>") + "<|tool_call|>".length();
int end   = modelOutput.indexOf("<|end_tool_call|>");
String toolCallJson = modelOutput.substring(start, end).trim();
// → {"name": "check_visa_status", "arguments": {"passport_number": "GH123456"}}

// 2. Extract name + args
JSONObject call = new JSONObject(toolCallJson);
String toolName = call.getString("name");
String toolArgs = call.getJSONObject("arguments").toString();

// 3. Dispatch via existing ToolDispatcher (already built in Phase 4)
String result = mToolDispatcher.dispatchByName(toolName, toolArgs);

// 4. Write result back to AgentSession
session.lastToolResult = result;
session.accumulatedContext += "\nTool result [" + toolName + "]: " + result;
```

`ToolDispatcher.dispatchByName()` is a new method to add — looks up `ToolRecord` by `toolName`
rather than by semantic search (we already know which tool to call at this point).

---

## File Structure

```
com/android/server/jarvis/
    JarvisService.java           ← existing — adds JarvisExecutor call in processQuery()
    core/ inference/ indexing/   ← existing — untouched
    search/ model/ tools/        ← existing — untouched
    │
    └── agent/                   ← NEW in Phase 5
        ├── JarvisExecutor.java  ← the loop: calls nodes in sequence, checks Router
        ├── AgentSession.java    ← ObjectBox entity (state object)
        ├── AgentTurn.java       ← ObjectBox entity (turn history)
        ├── RouterNode.java      ← deterministic routing via <|tool_call|> token check
        ├── PlanNode.java        ← CactusWrapper.complete(planPrompt + query)
        ├── RetrieveNode.java    ← JarvisService.processQuery() via Binder (RAG path only)
        ├── ToolNode.java        ← parse tool call tokens → ToolDispatcher.dispatchByName()
        └── RespondNode.java     ← CactusWrapper.complete(context + query) → final answer
```

`DreamWorker`, `SessionStore`, `MemoryConsolidator` are Phase 6 — do not build them in Phase 5.

---

## How Far Off Are We

| What we have | What Phase 5 adds |
|---|---|
| CactusWrapper (embed + complete) | `<|tool_call|>` token parsing in ToolNode |
| JarvisIndexWorker (RAG pipeline) | JarvisExecutor — the loop itself |
| ToolDispatcher (Phase 4) | AgentSession + AgentTurn ObjectBox entities |
| ObjectBox store + ModelRegistry | `ToolDispatcher.dispatchByName()` (one new method) |
| JarvisService Binder IPC | RouterNode updated for Gemma 4 tokens |
| Gemma 4 in llama.cpp (after Sam's pull) | ModelRegistry `"primary"` entry for Gemma 4 |

One solid sprint. The RAG pipeline is RetrieveNode. ToolDispatcher is ToolNode.
The genuinely new code is JarvisExecutor, AgentSession/AgentTurn, and RouterNode.

---

## Phase Mapping (updated)

```
Phase 1  ✅  Service architecture (Binder, FileObserver, IndexQueue)
Phase 2  ✅  Core RAG pipeline (ChunkingStrategy, MetadataSearch, JarvisIndexWorker)
Phase 3  ✅  ModelRegistry (named handle pairs, startup registration)
Phase 4  ✅  Tool Registry (ToolScanner, ToolDispatcher, AppRecord/ToolRecord)
Phase 5  📅  Agentic Loop — target model: Gemma 4 E2B or E4B
              PREREQUISITE: Sam pulls upstream llama.cpp into Cactus, confirms Gemma 4 loads
              — JarvisExecutor: stateful turn loop (Plan → Retrieve → Tool → Respond)
              — AgentSession + AgentTurn: ObjectBox entities, persisted after every node
              — RouterNode: deterministic <|tool_call|> token check, no extra model call
              — ToolNode: parse Gemma 4 tool call JSON, dispatchByName()
              — ModelRegistry: register Gemma 4 as "primary" at boot
              — Max turns: 5 hard ceiling (KV cache + thermal constraint)
Phase 6  📅  Memory Consolidation + Multimodal
              — DreamWorker: nightly WorkManager job, merges AgentTurn → UserContext facts
                (KAIROS autoDream pattern from Claude Code leak)
              — Voice/image input: Gemma 4 E2B/E4B handles audio + image natively
                hook into JarvisService as new Binder methods
              — Multi-agent: spawn sub-sessions for specialised tasks (Claude Code coordinator pattern)
```

---

## Key Design Principles (unchanged + new)

1. **The loop is not in JarvisService** — JarvisExecutor is a separate component. JarvisService stays single-purpose.
2. **State after every node** — AgentSession written to ObjectBox before the next node runs. Crash-resumable.
3. **Deterministic routing** — RouterNode checks `<|tool_call|>` token boundary. Zero ambiguity, no extra LLM call.
4. **Max turns is a hard ceiling** — 5 turns. KV cache growth and thermal throttling make this non-negotiable on mobile. Do not raise it without profiling on target hardware first.
5. **RetrieveNode calls JarvisService via Binder** — not into RAG internals directly. Abstraction boundary stays clean.
6. **ToolNode uses dispatchByName(), not semantic search** — by the time we're in ToolNode, Gemma 4 already told us the tool name. Semantic search was for the pre-Gemma era.
7. **Single model** — E2B or E4B, one loaded at a time. Loading two models doubles RAM. Not viable on 8GB phones.
8. **DreamWorker is Phase 6** — Phase 5 persists turns. Phase 6 consolidates them. Don't blur this line.

---

## Open Questions for Sam

The multi-model question from the original spec is now **answered**: single model, Gemma 4 E2B/E4B.

Remaining open questions:

1. **E2B vs E4B?** E2B fits more comfortably (~2.2GB runtime RAM), E4B is more capable (~3.8GB).
   Sam to benchmark both on the Nothing Phone 2. Quality of tool selection and plan generation
   is the metric. If E2B is good enough on those tasks, prefer it — leaves more headroom.

2. **Context window truncation policy** — `accumulatedContext` grows each turn.
   At what character count do we start dropping old turns? Keep original query + last 2 tool results?
   Sam to decide based on Gemma 4's actual 128K context and how much we're realistically filling.

3. **Tool result serialisation** — ToolNode gets a Bundle back from the app via ResultReceiver.
   Who converts Bundle → string that Gemma 4 can read in the next completion call?
   Recommendation: ToolNode owns this. ToolDispatcher returns raw Bundle, ToolNode serialises.

4. **Cactus API changes** — after the upstream pull, does `CactusWrapper.complete()` need new
   params to enable tool-call token output? Does Gemma 4 need a specific chat template?
   Sam confirms this during the Cactus pull step.

5. **Audio/image in Phase 5 or 6?** — Gemma 4 E2B supports audio and image natively.
   Should we expose a `processQuery(String query, byte[] image)` overload in Phase 5,
   or keep Phase 5 text-only and add multimodal in Phase 6?
   Recommendation: text-only in Phase 5, multimodal in Phase 6. Keep Phase 5 scope tight.

---

## Chaining Scenarios — dev.talk Demo

The agentic loop chains tools automatically. Gemma 4 outputs a `<|tool_call|>` block,
ToolNode dispatches it, result goes into `accumulatedContext`, RouterNode checks the next
plan output for another tool call, loop continues until RespondNode produces the final answer.

No code changes are needed for chaining — it is wired. These scenarios work with the current
tool set once deployed on ARM hardware with Gemma 4 loaded.

### Scenario 1 — "I'm heading into a meeting"
```
User: "I'm heading into a meeting in 10 minutes"

Turn 1 — PlanNode: model decides → set_dnd + create_calendar_event + send_sms
Turn 2 — ToolNode: set_dnd(mode="priority")          → "DND: priority contacts only"
Turn 3 — RouterNode: another tool call detected
Turn 4 — ToolNode: create_calendar_event(...)         → "Event created: Meeting..."
Turn 5 — RouterNode: another tool call detected
Turn 6 — ToolNode: send_sms(to=..., body="In a meeting, back soon") → "SMS sent"
Turn 7 — RouterNode: no more tool calls
Turn 8 — RespondNode: "Done — DND is on, your meeting is in the calendar,
                       and I've texted your contacts."
```

### Scenario 2 — "Good morning"
```
User: "Good morning"

Turn 1 — PlanNode → get_notifications + get_battery_status + media_control
Turn 2 — ToolNode: get_notifications()    → "4 notifications: WhatsApp(2) Gmail(1) BBC(1)"
Turn 3 — ToolNode: get_battery_status()   → "Battery: 82% (charging)"
Turn 4 — ToolNode: media_control(action="play") → "Playing"
Turn 5 — RespondNode: "Morning! You have 4 notifications — 2 WhatsApp, 1 Gmail, 1 BBC News.
                       Battery is at 82% and still charging. I've started your music."
```

### Scenario 3 — "I'm going to bed"
```
User: "I'm going to bed"

Turn 1 — PlanNode → set_alarm + set_dnd + set_brightness
Turn 2 — ToolNode: set_alarm(hour=7, minute=0, label="Wake up")  → "Alarm set for 07:00"
Turn 3 — ToolNode: set_dnd(mode="alarms")   → "DND: alarms only"
Turn 4 — ToolNode: set_brightness(level=0)  → "Brightness set to 0%"
Turn 5 — RespondNode: "Goodnight — alarm set for 7am, DND on (alarms only), screen dimmed."
```

---

## Research References

All decisions in this document are backed by the following sources.
Links are kept here so the team can audit the reasoning.

**Gemma 4:**
- Model announcement + capabilities: https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/
- HuggingFace launch post (sizes, benchmarks, GGUF weights): https://huggingface.co/blog/gemma4
- Native function calling docs: https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4
- Arm performance (5.5× prefill, Nothing Phone 2 hardware target): https://newsroom.arm.com/blog/gemma-4-on-arm-optimized-on-device-ai
- llama.cpp GGUF setup + quantization guide: https://avenchat.com/blog/run-gemma-4-with-llama-cpp
- llama.cpp audio support issue (still in progress): https://github.com/ggml-org/llama.cpp/issues/21325

**Agentic frameworks:**
- LangGraph internals (state, nodes, edges): https://langchain.com/langgraph
- LangGraph checkpointing architecture: https://deepwiki.com/langchain-ai/langgraph/4.1-checkpointing-architecture
- LangGraph tool node pattern: https://sangeethasaravanan.medium.com/building-tool-calling-agents-with-langgraph-a-complete-guide-ebdcdea8f475
- Claude Code leak (QueryEngine, coordinator, KAIROS): https://github.com/nirholas/claude-code
- Claude Code leak analysis: https://dev.to/varshithvhegde/the-great-claude-code-leak-of-2026

**Mobile agent frameworks (reference only — not directly usable):**
- DroidRun (Android agent via Accessibility APIs, 91.4% on AndroidWorld): https://github.com/droidrun/droidrun
- OpenClaw (autonomous agent, TS/Node, has Android companion app): https://github.com/openclaw/openclaw

**Mobile LLM constraints:**
- On-device LLMs state of the art 2026: https://v-chandra.github.io/on-device-llms/
- KV cache and I/O on mobile: https://www.mdpi.com/2227-7390/13/22/3689
