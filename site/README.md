# JarvisOS Field Guide (the explainer page)

One page that explains JarvisOS: the system map, how a query, Binder, a
session and storage work, the 15 decisions, the security view, how the team
shares the build server, and a live feed of what changed.

| File | What it is |
|---|---|
| `index.html` | The page. One self-contained file, written to the claude.ai Artifact page contract (no `<html>`/`<head>`/`<body>` tags of its own). |
| `gen-data.py` | Writes `data.json`. Python 3, standard library only. Reads git and the decisions record; changes nothing else. |
| `data.json` | The live data the page fetches: commits, uncommitted paths, decision titles and statuses, and the time it was generated. |

## Regenerate the data

```
python3 /srv/jarvis/lineage/vendor/jarvisos/site/gen-data.py
```

It reads the last 60 commits of `frameworks/base`, `vendor/jarvisos`,
`vendor/cactus` and `vendor/llama.cpp` (author name, date, subject, short
hash and the `Server-user:` trailer), `git status --porcelain` of
`vendor/jarvisos`, and each decision's title and status from
`skills/jarvisos-architecture/references/decisions.md`. No email addresses
are written.

## Republish

Publish `index.html` as the Artifact page, with `data.json` as a supporting
file at the path `data.json` (the page calls `fetch('data.json')`). To update
the same link, publish again to the same Artifact URL. If only the data
changed, regenerate it and republish; the page itself does not need an edit.

If `data.json` is missing, the page still works: the change feed shows a
"no signal" notice and the decisions keep the statuses embedded in the page.

## When the sources change

- **Decision statuses and titles** follow `decisions.md` through `data.json`.
- **The rest of the decision text** (why, turned down, in the code, for the
  team, check yourself) is embedded in `index.html`, inside the
  `<details class="dec">` blocks. It was taken from `decisions.md` on
  10 Nov 2026. Edit those blocks when the record changes.
- **The system map, walkthroughs, security view and team section** are data
  objects near the top of the page's `<script>` (`COMP`, `WALKS`, `ENTRIES`,
  `DEFS`, `DAY`). Check them against the code before a talk.

Nothing here is committed or pushed. Kevin decides.
