---
name: "jarvisos-ax"
description: "Use for JarvisOS Agentic Xperience (AX) work: the agent tool-calling data model (ERD), grants and consent, schema vs Binder transport, or AX explainers and decks in STE100."
---

# JarvisOS AX (Agentic Xperience)

AX is Kevin's own term: UX thinking aimed at agents instead of users. It asks how an agent discovers, chooses, gets permission for, and calls a tool, and how the user sees and controls that. It is not an industry term, so define it whenever it appears in public material.

JarvisOS is a LineageOS 22.2 ROM for the Nothing Phone 2 (Pong). On-device inference goes through `Inference.java`, with two engines behind it: Cactus and llama.cpp. ObjectBox stores metadata and index pointers only, never embeddings.

## Core decision: schema and transport are separate

Tools are described in an MCP-shaped / OpenAI function-format JSON schema, which is what the engines feed the model. Tools are dispatched over Binder, not MCP over HTTP (today: a protected broadcast with a `ResultReceiver` for app tools, in-process for `@system/` tools; see `references/code-today.md`):

1. Android kills backgrounded HTTP servers.
2. Localhost HTTP has known CVEs.
3. Binder gives kernel-enforced UID/PID auth, microsecond latency, and no network attack surface.

Always keep `TOOL.schema` (model-facing contract) separate from `TOOL_PROVIDER.aidlInterface` (transport).

## Reference ERD

```mermaid
erDiagram
    MODEL ||--o{ MODEL_INDEX : owns
    MODEL ||--o{ SESSION : serves
    SESSION ||--|{ TURN : contains
    TURN ||--o| INTENT : classified_as
    TURN ||--o{ TOOL_CALL : emits
    TOOL ||--o{ TOOL_CALL : invoked_by
    TOOL_PROVIDER ||--|{ TOOL : exposes
    TOOL_PROVIDER ||--o{ GRANT : holds
    GRANT }o--|| TOOL : scopes
    TOOL_CALL ||--o| TOOL_RESULT : returns
    TOOL_CALL ||--|| AUDIT_EVENT : logged_as
    MEMORY_ITEM }o--|| MODEL_INDEX : pointed_by

    MODEL { string modelHandle PK; string role "router|rag|asr|tts"; string quant }
    MODEL_INDEX { string indexHandle PK; string cactusIndexId }
    SESSION { long id PK; string originPackage; long startedAt }
    TURN { long id PK; string role; string text; long tokenCount }
    INTENT { long id PK; string label; float confidence }
    TOOL { string name PK; json schema "OpenAI fn format"; string sideEffect "read|write|irreversible" }
    TOOL_PROVIDER { string package PK; int uid; string aidlInterface }
    GRANT { long id PK; string scope; string consentMode "always|ask|never" }
    TOOL_CALL { long id PK; json args; string status; long latencyMs }
    TOOL_RESULT { long id PK; json payload; bool truncated }
    AUDIT_EVENT { long id PK; long ts; string decision }
    MEMORY_ITEM { long id PK; string source; string cactusIndexId }
```

This is a draft (10 Nov 2026). Treat the code under `vendor/jarvisos/` as the source of truth, and report where it disagrees with the ERD before you change either.

`references/code-today.md` maps each entity to the code as of 10 Nov 2026 and lists every known disagreement, including two in the sections around this one (the inference boundary and Cactus on x86_64). Read it before you design against the ERD. The service code is in `frameworks/base/services/jarvis/`, not only `vendor/jarvisos/`.

## Open questions (settle in this order)

1. Tool ownership: apps register their own tools, or Jarvis curates the registry? Decides whether `TOOL` is keyed by `name` or `(package, name)`.
2. `sideEffect` placement: current position is on `TOOL`, not `GRANT`. It decides act silently, ask first, or refuse.
3. `GRANT` granularity: per tool, per provider, or per intent.
4. Router vs RAG: does `INTENT` come only from the router model, or can the RAG model reclassify mid-session?
5. Result persistence: store a hash and summary of `TOOL_RESULT.payload`, not the full payload, under the ObjectBox rule.

What the code already answers in part (Q1 both, keyed in effect by package and name; Q2 a yes/no `requiresConfirmation` on the tool; Q5 a 600-character summary kept 24 h) is in `references/code-today.md`.

When one is settled, update this list with the decision and the date.

## Constraints

1. Inference and agent logic move out of `system_server` (TASK_2). Do not design entities that assume in-process access.
2. `Inference` / `InferenceEngine` is the swappable boundary (Cactus and llama.cpp behind it). Entities reference vectors only through index pointers (`cactusIndexId` today, an engine ID; the 2 Oct plan to make it a JarvisOS-owned ID is not done).
3. On the x86_64 emulator, llama.cpp is the default engine; Cactus builds there through a SIMDe shim but runs only when chosen by name. Validate schema work on the emulator with llama.cpp, or in host tests.

## Writing AX explainers in ASD-STE100

When asked for an STE100 explainer (HTML page, doc or slide text):

1. One topic per sentence.
2. Procedural sentences: 20 words maximum. Descriptive sentences: 25 words maximum.
3. Active voice, simple tenses.
4. One instruction per sentence in procedures.
5. Use each word in its approved meaning. Keep technical names (`TOOL_CALL`, Binder) as technical names.
6. No phrasal verbs. No "-ing" words used as nouns.

HTML explainers are one self-contained page. Render the ERD (Mermaid from a CDN is acceptable). Always include one end-to-end example: user request, intent, grant check, Binder call, result, audit event.

## AX decks

Default outline (8 to 10 slides): problem, AX definition, schema vs transport, the ERD, the end-to-end example, the open questions, next steps.

On the build server, build `.pptx` with `python-pptx` or `pptxgenjs`. Render the ERD to PNG with `@mermaid-js/mermaid-cli` and place it on its slide. Keep STE100 rules for slide text. If a Google Drive connector exists, upload the `.pptx` so it opens as Google Slides; otherwise leave it in `docs/ax/` and report the path.

## Working rules

1. Do not commit or push. Kevin decides when to push.
2. Run review tasks (ERD vs code) before building explainers or decks, so the presentation matches the code.
3. Kevin's prose style: conclusions first, Google developer docs style, no dashes in flowing prose.
