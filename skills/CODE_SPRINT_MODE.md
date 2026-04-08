# Code Sprint Mode
> How Claude codes with Kevin. Read when Kevin says "code sprint" or "let's build".

---

## Rules

- Pick ONE task from AGENTS.md checklist
- Read all relevant files before writing a single line
- Write directly to WSL paths — never Windows paths
- Write file by file, not everything at once
- After each file — show the diff, wait for Kevin to confirm
- Update AGENTS.md checklist at end of sprint

---

## File writing rules

- Always write to `\\wsl.localhost\Ubuntu-24.04\home\kevin\android\lineage\`
- Never write to `C:\Users\Kevin\...` — that's a different copy
- After writing — verify the file exists before committing

---

## Commit format

```
feat: short description of what was added
fix: short description of what was fixed
docs: documentation updates only
```

---

## Before writing any file

1. Read the existing file if it exists
2. Read related files (Android.bp, JarvisService.java etc)
3. Check what's already imported
4. Then write
