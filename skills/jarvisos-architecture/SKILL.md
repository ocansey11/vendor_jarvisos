---
name: jarvisos-architecture
description: The JarvisOS architecture decisions and the reasons behind them, checked against the code, and how to teach them. Use when Kevin wants to understand, explain or present any part of JarvisOS (why system_server, Binder, two engines, the fast path, sessions, the fence, storage, history, the build server), when a teammate needs the why behind a part they are changing, when a design from another chat or skill has to be checked against the code, or for security work such as prompt injection testing.
---

# JarvisOS architecture: the decisions and why

Most of the JarvisOS code was written by Claude, with Kevin making the
decisions. So Kevin can have gaps in his understanding of how his own
decisions turned into code. This skill closes them. It serves Kevin first,
then Sam and Desmond.

## Where things are

- `references/decisions.md`: 15 decisions. For each: what, why, what was
  turned down, where in the code, how far it is built, what it means for the
  team, and a question to check understanding. Start here.
- `references/skill-review.md`: what is out of date in Kevin's account
  skills (`cactus-integration`, `jarvisos-storage-layer`,
  `lineageos-pong-build`) compared with the code.
- `vendor/jarvisos/ARCHITECTURE.md`: the service's parts and how they connect.
- `vendor/jarvisos/AGENT_EXPERIENCE.md` and the `jarvisos-ax` skill: AX,
  sessions, and the tool data model (ERD draft).
- `vendor/jarvisos/PROMPT_INJECTION.md`: the attack surface for injected
  instructions and Sam's test plan.
- The `jarvis-presentation` skill: what went wrong while building
  (roadblocks), plain-language concepts, and the build story.
- Reading lists: `jh read` (`overview`, `query`, `tictactoe`).

## Rule 1: the code is the source of truth

Kevin discusses JarvisOS in many chats. Designs, skills and handoffs from
those chats can be behind the code, or ahead of it. Before anything from
another chat goes into a doc or skill:

1. Map each claim to a file and line in the code.
2. Say what is built, what is partly built, what is not built, and what the
   code does differently.
3. Report that to Kevin before changing the design or the code.
4. Update the repo copy, so the team's Claudes get the corrected version.

## Rule 2: when Kevin wants to learn a part (teaching mode)

Kevin learns by explaining. Do not lecture.

1. Ask which part, or offer the next decision from `decisions.md` he has
   not covered.
2. Give the decision in three sentences: what, why, what was turned down.
3. Open the code with him: the reading list for that path (`jh read NAME`),
   or the two or three files named in the entry. Point at the lines that
   carry the decision.
4. If it can be shown, show it on the emulator (`jh q`, `jh trace`,
   `jh logs`), so it is not only words.
5. Ask the entry's "Check yourself" question, and let him answer before you
   say anything. Then ask him to explain the decision back as he would to
   Sam. Correct only what is wrong.
6. Note what he found hard. If an entry was unclear, improve the entry.

Keep sessions to one or two decisions. Concepts he needs first (Binder,
system_server, SELinux, broadcasts) are in the `jarvis-presentation`
skill's `references/concepts.md`.

## Rule 3: explaining to the team

For Sam and Desmond, lead with "For the team" in each entry: what the
decision means for the change they are making. Point them to the reading
list and the test that proves it.

## Keeping it true

When a change in the code alters a decision, update the entry in the same
commit (date it). When a roadblock changes a decision, link the roadblock
number. Do not commit or push; Kevin decides.
