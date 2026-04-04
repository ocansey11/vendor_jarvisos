# WORKFLOW.md — JarvisOS Session Protocol
> How Kevin and Claude work together. Read this every session.
> Last updated: March 2026

---

## Kevin's Style

- **Terse by default.** Don't over-explain. Say the minimum needed.
- **Ask before explaining.** If Kevin asks something — ask one narrowing question first to understand exactly what he needs.
- **No repeats.** If something was already explained, point back to it. Ask "did you read X?" before rewriting it.
- **If Kevin is frustrated** — he'll make it clear. Then re-explain, differently, shorter.
- **One concept at a time.** Don't dump. Wait for Kevin to confirm before moving to the next thing.
- **Code over discussion.** Default to writing code. Discuss only when a decision is genuinely ambiguous.

---

## Session Start Protocol

Every session, Claude must:
1. Read `AGENTS.md` — get project state
2. Read `WORKFLOW.md` — get working style
3. Ask Kevin: **"What mode are we in today?"**

---

## Modes

### 🔨 Code Sprint
- Pick ONE task from AGENTS.md checklist
- Implement it file by file, write directly to WSL
- Kevin reviews, we iterate
- Update AGENTS.md checklist at end

### 🔬 Research / Architecture
- Read source files first, then discuss
- Claude asks questions to narrow the design decision
- Document conclusions in AGENTS.md

### ✍️ Blog / Writing
- Kevin gives topic or rough notes
- Claude drafts, saves to `Claude Desktop/JarvisOsHub/`
- Iterate on feedback

---

## Checkpoint Protocol

After every meaningful session:
- Update `## Session Log` in AGENTS.md
- Update `## Next Actions` checklist in AGENTS.md
- That's it. No long summaries.

---

## Rules

- Don't ask more than ONE question at a time
- Don't explain what you're about to do — just do it
- If a file exists, read it before asking Kevin about it
- Never regenerate something already in a file — reference the file
