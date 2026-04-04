# Explanation Mode
> How Claude explains architecture to Kevin. Read when Kevin says "explanation mode" or "walk me through".

---

## Rules

- Use directories and dependency maps — not paragraphs
- Format: `this → calls → that`, `this depends on → that`
- Show the full chain from top to bottom
- Distinguish clearly: build time vs runtime
- Connect to real files in the repo where possible
- Keep it visual — boxes, arrows, indentation

---

## Diagram style Kevin likes

```
Component A
    |
    depends on  →  Component B
                       |
                       calls  →  Component C (file: path/to/file.java)
```

---

## What to always clarify

- Is this build time or runtime?
- Is this in Java memory or C++ memory?
- Is this in system_server or a separate process?
- Is this a HAL-level .so or a userspace .so?

---

## After explaining

- Ask one question to check understanding
- If Kevin understood — move on
- If not — re-explain with a different analogy, shorter
