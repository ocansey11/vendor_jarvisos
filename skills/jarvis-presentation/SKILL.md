---
name: jarvis-presentation
description: The record of roadblocks hit while building JarvisOS (an Android ROM with on-device AI as a system service, on LineageOS for the Nothing Phone 2), kept for Kevin's talk about the project. Use this whenever Kevin works on his Jarvis talk, slides or write-up, asks for the story or timeline of the build, wants "what went wrong", "roadblocks", "lessons", "before and after code", or a war story for a slide. Also use it while working on JarvisOS itself, whenever a build breaks, a test fails, or something that looked finished turns out not to work — that is the moment to add an entry, because the details are gone a day later.
---

# Jarvis presentation: the roadblock timeline

Kevin is giving a talk about building JarvisOS. The most useful material for
it is not the architecture, it is the sequence of things that went wrong and
what each one taught. This skill keeps that sequence, with the exact code
before and after, so it can be turned into slides or a narrative on request.

Three reference files hold the material. Read the relevant one before
answering; do not reconstruct the story from memory.

- `references/roadblocks.md` — every roadblock in order, with the error text
  and the before/after code.
- `references/how-it-was-built.md` — who did what. Kevin directed the work and
  made the decisions; Claude (Claude Code on the build server) did the
  hands-on building, fixing and testing. It also lists what Claude got wrong.
  Use it whenever the talk touches on how the project was actually built, and
  keep that account straight: do not present Claude's work as Kevin's typing,
  or Kevin's decisions as Claude's.
- `references/concepts.md` — plain-language explanations of the technical
  ideas behind each roadblock, written for Kevin to learn from, each with a
  question to check his understanding.

## Three jobs

### 1. Telling the story

When Kevin asks for the timeline, a slide, or an angle for the talk:

- Read `references/roadblocks.md` and work from what is written there.
- Lead with what a listener can picture: what he expected, what happened
  instead, the one line of code that was wrong. The entries are already
  ordered by time; keep that order unless he asks for themes.
- Quote code exactly as recorded. A before/after pair is only convincing if
  it is the real code, so never tidy it up or paraphrase it into something
  that was not actually in the repo.
- Keep the evidence level honest. Each entry says how the problem was found
  and how far the fix has been proven (compiles, passes on the server, runs
  on the emulator, runs on the phone). Carry that through: "fixed and seen
  working on the emulator" and "fixed, not yet re-tested" are different
  claims, and an audience member who builds ROMs will notice if they blur.
- The recurring lesson, which several entries illustrate from different
  directions: *compiles*, *ROM builds*, *boots*, and *works* are four
  separate milestones. The talk is stronger when each roadblock is tied to
  the milestone it was hiding behind.

For slides, one roadblock per slide works well: a short title, the symptom in
one line, the before/after code (trimmed to the lines that matter, with the
trim marked), and the lesson in one line. If Kevin wants a deck, use whatever
slide tool the session offers; this skill supplies the content, not the
format.

### 2. Keeping the record

While working on JarvisOS, add an entry to `references/roadblocks.md` when
something breaks or a wrong assumption surfaces. Do it at the time: the error
text, the exact old code and the reason it looked fine are easy to capture
now and hard to recover later.

An entry needs:

- **When** — date and local time, and which session of work it was.
- **What we expected / what happened** — one or two plain sentences each.
- **How it was found** — which test, build step or log line exposed it.
- **Cause** — the actual mechanism, not just "it was wrong".
- **Before / after** — the real code, copied from `git diff` or `git show`,
  trimmed to the relevant lines. Include the file path.
- **Proven to** — the furthest level at which the fix has been seen working.
- **Commit** — repo and hash once committed.
- **For the talk** — one line on why an audience would care.

Also record predictions that turned out wrong, and problems found but not yet
fixed. Both make better talk material than a clean list of wins, and leaving
them out would make the timeline misleading.

When a fix is later proven at a higher level (for example the phone arrives),
update the entry's "Proven to" line rather than adding a new entry.

After a working session, add a dated section to
`references/how-it-was-built.md` in the same style: what Kevin decided, what
Claude did, what went wrong on Claude's side. If a new roadblock rests on an
idea that `references/concepts.md` does not cover, add a section there too.

The files are in the `vendor_jarvisos` repo, so commit changes to them along
with the work they describe.

### 3. Helping Kevin learn the material

Kevin wants to understand the technical side well enough to present it and
take questions. When he asks to review, revise, be quizzed, or "explain X
again":

- Work from `references/concepts.md`. Each section ends with a question; ask
  it, let him answer in his own words, then fill in what was missing rather
  than reciting the section back.
- Tie every explanation to the roadblock it came from. He was there for the
  failure, so the concrete case is the fastest way in.
- If he explains something on a slide in a way that is not quite right, say
  so plainly and give the correct version. A small inaccuracy is easy to fix
  in rehearsal and awkward to be caught on in front of an audience.
- Likely audience questions are worth rehearsing: "what has actually run on a
  phone?" (nothing yet), "how much of this did the AI write?" (see
  `how-it-was-built.md`), "why not just make it an app?" (section 1 of the
  concepts).
