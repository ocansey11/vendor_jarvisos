# AGENTIC_LOOP.md — JarvisOS Agent Orchestration Architecture
> Research + design spec. No code this month. This is the blueprint.
> Last updated: April 2026

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

Claude Code leaked on March 31 2026 from Anthropic's npm registry via an exposed sourcemap.
Key architectural patterns relevant to JarvisOS:

**QueryEngine.ts** — the core agentic loop:
- Streaming responses from the LLM
- Tool-call loop: model returns a tool_use block → execute tool → feed result back → repeat
- Loop exits when model returns a text block with no tool calls (done) or hits max iterations
- Strict Write Discipline: state only updates after confirmed successful action

**coordinator/** — multi-agent orchestration (Swarm pattern):
- Sub-agents spawned via AgentTool
- Each sub-agent has its own tool set and context window
- Coordinator routes tasks to the right sub-agent

**memdir/** + **tasks/** + **state/** — the three-layer memory:
- `tasks/` = in-flight work (what the agent is currently doing)
- `memdir/` = persistent memory across sessions (what it has learned)
- `state/` = current execution snapshot (resumable)

**KAIROS flag** — unreleased autonomous background daemon:
- Background sessions while idle
- autoDream: nightly memory consolidation
- Merges observations, removes contradictions, converts vague insights to verified facts
- This is exactly what JarvisOS's proactive observer loop should become

**Key principle stolen directly:**
> The agent treats its own memory as a "hint" and verifies against ground truth before acting.
> It never trusts stored state blindly.

---

## What LangGraph Gets Right

LangGraph's three primitives map cleanly to what we need:

| LangGraph concept | What it is | JarvisOS equivalent |
|---|---|---|
| **State** | Shared typed object flowing through the graph | `AgentSession` (Java record/class in ObjectBox) |
| **Node** | A function that reads state, does work, returns state update | A stage in `JarvisExecutor` (embed, retrieve, complete, dispatch) |
| **Edge** | Conditional routing between nodes | `RouterNode` — reads model output, decides next stage |

The key insight from LangGraph: **state is the agent's working memory**. Every node reads from it and writes to it. The graph is just the routing logic on top.

LangGraph's checkpointing (durable execution, resume after failure) is the equivalent of persisting `AgentSession` to ObjectBox after each node completes.

---

## JarvisOS Agent Architecture — Phase 5

### The core loop (what we're building)

```
User query (text or voice)
    |
    v
AgentSession created (ObjectBox, persisted immediately)
    |
    v
┌─────────────────────────────────────────────────────┐
│                  JarvisExecutor                      │
│                  (the agentic loop)                  │
│                                                      │
│  PLAN node        — model decides what to do         │
│       |                                              │
│  RETRIEVE node    — RAG lookup (existing Phase 2)    │
│       |                                              │
│  TOOL node        — ToolDispatcher fires intent      │
│       |                                              │
│  RESPOND node     — model generates response         │
│       |                                              │
│  ROUTER           — done? loop? escalate?            │
│       |                                              │
│  (repeat until ROUTER says DONE or MAX_TURNS hit)    │
└─────────────────────────────────────────────────────┘
    |
    v
Response returned via Binder to caller
AgentSession updated in ObjectBox (becomes long-term memory)
```

### AgentSession — the state object

This is the LangGraph State equivalent. Everything the loop needs lives here.

```java
@Entity
public class AgentSession {
    @Id long id;
    String sessionId;           // UUID, stable across turns
    String originalQuery;       // what the user asked
    String currentPlan;         // what the model decided to do
    String lastToolResult;      // output from last tool call
    String accumulatedContext;  // retrieved docs + tool results so far
    int turnCount;              // how many times the loop has run
    int maxTurns;               // safety ceiling (default: 5)
    String status;              // PLANNING | RETRIEVING | TOOL_CALL | RESPONDING | DONE | FAILED
    long createdAt;
    long lastUpdatedAt;
    ToMany<AgentTurn> turns;    // full turn history
}

@Entity
public class AgentTurn {
    @Id long id;
    String role;                // model | tool | user
    String content;             // what was said/returned
    String toolName;            // if role == tool
    String toolArgs;            // JSON
    long timestamp;
    ToOne<AgentSession> session;
}
```

### The Router — the conditional edge

This is the most important piece. After each model call, the router reads the output and decides the next node.

```java
public class RouterNode {
    public enum Next {
        RETRIEVE,    // model asked for more context
        TOOL_CALL,   // model called a tool
        RESPOND,     // model is generating final answer
        DONE,        // response complete, exit loop
        FAILED       // error or max turns hit
    }

    public Next route(AgentSession session, String modelOutput) {
        // Parse model output — is it a tool call or a text response?
        if (isToolCall(modelOutput)) return Next.TOOL_CALL;
        if (needsMoreContext(modelOutput, session)) return Next.RETRIEVE;
        if (session.turnCount >= session.maxTurns) return Next.FAILED;
        return Next.DONE;
    }
}
```

No LLM needed for routing — deterministic post-processing, same principle we used at the hackathon.

---

## File Structure

This is the answer to your second question — no more files dumped into `rag/`.

```
frameworks/base/services/core/java/com/android/server/
    rag/                          ← existing, Phase 1-4 files stay here
        RagService.java
        CactusWrapper.java
        MetadataSearch.java
        ... (all current files)

    jarvis/                       ← NEW — the agentic layer
        agent/
            JarvisExecutor.java   ← the main agentic loop
            AgentSession.java     ← state object (ObjectBox entity)
            AgentTurn.java        ← turn history (ObjectBox entity)
            RouterNode.java       ← conditional routing logic
            PlanNode.java         ← planning stage (first model call)
            RetrieveNode.java     ← wraps existing RAG pipeline
            ToolNode.java         ← wraps existing ToolDispatcher
            RespondNode.java      ← final generation stage
        memory/
            SessionStore.java     ← ObjectBox ops for AgentSession
            DreamWorker.java      ← nightly memory consolidation (KAIROS equivalent)
            MemoryConsolidator.java ← merges turns into long-term facts
        voice/                    ← Phase 6 (don't touch yet)
```

The `rag/` package remains unchanged. `jarvis/agent/` sits on top of it.
`RetrieveNode` calls `RagService` via the existing Binder interface — no coupling into RAG internals.

---

## How Far Off Are We?

Honest assessment:

| What we have | What's missing |
|---|---|
| Cactus inference (embed + complete) | Loop that feeds output back in |
| RAG retrieval (MetadataSearch + vector) | Session state that persists across calls |
| Tool Registry design (Phase 4) | ToolNode that actually fires tools and gets results back |
| ObjectBox for storage | AgentSession + AgentTurn entities |
| Binder IPC working | Nothing — JarvisExecutor just calls through existing stack |

We're about **one solid phase** away. The RAG pipeline is the `RetrieveNode`. The Tool Registry is the `ToolNode`. The only genuinely new thing is the loop itself (`JarvisExecutor`), the state object (`AgentSession`), and the router.

The DreamWorker / memory consolidation is Phase 6 territory — don't build it until the basic loop is stable.

---

## Phase Mapping

```
Phase 1  ✅  RAG service architecture (Binder, FileObserver, IndexQueue)
Phase 2  ✅  Core RAG pipeline (ChunkingStrategy, MetadataSearch, RagIndexWorker)
Phase 3  ✅  ModelRegistry (named handle pairs, startup registration)
Phase 4  🔄  Tool Registry (ToolScanner, ToolDispatcher, AppRecord/ToolRecord)
Phase 5  📅  Agentic Loop (JarvisExecutor, AgentSession, RouterNode)
              — JarvisExecutor wires RAG + Tools into a stateful turn loop
              — AgentSession persisted to ObjectBox after each turn (resumable)
              — RouterNode: deterministic, no extra model call
              — Max turns guard: prevents infinite loops (ceiling: 5 turns)
              — Voice sits here too as a client layer on top of the loop
Phase 6  📅  Memory Consolidation (DreamWorker, nightly sessions, long-term facts)
              — Inspired by KAIROS autoDream pattern from Claude Code leak
              — Merges AgentTurn history into UserContext facts
              — Runs via WorkManager, charging only, same pattern as RagIndexWorker
```

---

## Key Design Principles for Phase 5

1. **The loop is not in RagService** — JarvisExecutor is a separate component. RagService stays single-purpose.
2. **State after every turn** — AgentSession is written to ObjectBox before the next turn starts. If system_server crashes, the session can resume.
3. **Deterministic routing** — RouterNode parses model output with string matching, not another model call. Same hackathon principle.
4. **Max turns is a hard ceiling** — not a suggestion. Loop exits at 5 turns regardless. Prevents memory explosions in system_server.
5. **RetrieveNode calls RagService via Binder** — not directly. Keeps the abstraction boundary clean.
6. **ToolNode fires an Intent and waits for a result** — same broadcast + callback AIDL pattern from TOOL_REGISTRY.md. No coupling into tool internals.
7. **DreamWorker is NOT Phase 5** — memory consolidation is Phase 6. Phase 5 just persists turns. Don't over-engineer the first loop.

---

## Open Questions for Sam

1. **Planning model vs execution model** — does the PLAN node use the same model as RESPOND, or a smaller one for routing? Sam's NLP background relevant here.
2. **Context window budget** — how many AgentTurns can we pack into a single Cactus completion call before we hit the context limit? Need a chunking strategy for long sessions.
3. **Tool result parsing** — ToolNode gets a Bundle back from the app. Who serialises that to a string the model can read? Parser lives in ToolNode or ToolDispatcher?
4. **Multi-intent queries** — Sam's sliding window splitting work from the hackathon. Does that live in PlanNode or in the existing ChunkingStrategy?

---

## References

- LangGraph state machine pattern: https://github.com/langchain-ai/langgraph
- Claude Code leak architecture (QueryEngine, coordinator, memdir): https://github.com/nirholas/claude-code
- KAIROS autoDream pattern: dev.to/varshithvhegde/the-great-claude-code-leak-of-2026
- Strict Write Discipline: same source as above
- DroidRun (mobile agent framework for reference): https://github.com/droidrun
