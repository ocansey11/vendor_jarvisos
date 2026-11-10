#!/usr/bin/env python3
"""Write data.json for the JarvisOS explainer page (index.html, same folder).

Reads, and changes nothing:
  - the last 60 commits of the four owned repos (with the Server-user trailer),
  - `git status --porcelain` of vendor/jarvisos,
  - each decision's title and status from the decisions record.

Python 3, standard library only. Author names only; no email addresses.

    python3 vendor/jarvisos/site/gen-data.py
"""
import datetime
import json
import re
import subprocess
import sys
from pathlib import Path

SITE = Path(__file__).resolve().parent
JARVISOS = SITE.parent                      # vendor/jarvisos
ROOT = JARVISOS.parent.parent               # the LineageOS tree
DECISIONS = JARVISOS / "skills/jarvisos-architecture/references/decisions.md"
OUT = SITE / "data.json"

REPOS = ["frameworks/base", "vendor/jarvisos", "vendor/cactus", "vendor/llama.cpp"]
COMMITS = 60
FS, RS = "\x1f", "\x1e"
LOG_FORMAT = FS.join([
    "%h", "%cI", "%an",
    "%(trailers:key=Server-user,valueonly,separator=%x2C)",
    "%s",
]) + RS
EMAIL = re.compile(r"<?\b[\w.+-]+@[\w-]+(\.[\w-]+)+\b>?")


def git(repo, *args):
    """Runs git in a repo of the tree and returns stdout ('' on failure)."""
    cmd = ["git", "-c", "safe.directory=*", "-C", str(ROOT / repo), *args]
    try:
        done = subprocess.run(cmd, capture_output=True, text=True, timeout=60,
                              errors="replace")
    except (OSError, subprocess.TimeoutExpired) as e:
        print(f"gen-data: {repo}: {e}", file=sys.stderr)
        return ""
    if done.returncode != 0:
        print(f"gen-data: {repo}: git {args[0]} failed: {done.stderr.strip()}",
              file=sys.stderr)
        return ""
    return done.stdout


def no_email(text):
    return EMAIL.sub("", text).strip()


def commits(repo):
    out = git(repo, "log", f"-{COMMITS}", f"--format={LOG_FORMAT}")
    rows = []
    for record in out.split(RS):
        parts = record.strip("\n").split(FS)
        if len(parts) != 5:
            continue
        short, date, author, server_user, subject = parts
        rows.append({
            "hash": short,
            "date": date,
            "author": no_email(author),
            "server_user": no_email(server_user.strip().strip(",")),
            "subject": no_email(subject),
        })
    return rows


def uncommitted(repo):
    rows = []
    for line in git(repo, "status", "--porcelain").splitlines():
        if len(line) < 4:
            continue
        code, path = line[:2], line[3:]
        kind = ("untracked" if code == "??" else
                "deleted" if "D" in code else
                "added" if "A" in code else
                "renamed" if "R" in code else "modified")
        rows.append({"code": code.strip(), "kind": kind, "path": path})
    return rows


def decisions():
    """Title and status of each '## N. Title' entry in the decisions record."""
    try:
        text = DECISIONS.read_text(encoding="utf-8")
    except OSError as e:
        print(f"gen-data: {e}", file=sys.stderr)
        return [], ""
    checked = re.search(r"Checked against the code on ([^.]+)\.", text)
    rows = []
    heads = list(re.finditer(r"^## (\d+)\. (.+)$", text, re.M))
    for i, head in enumerate(heads):
        end = heads[i + 1].start() if i + 1 < len(heads) else len(text)
        body = text[head.end():end]
        m = re.search(r"\*\*Status\.\*\*\s*(.+?)(?:\n\s*\n|\Z)", body, re.S)
        status = " ".join(m.group(1).split()) if m else ""
        title = head.group(2).strip()
        low = status.lower()
        if low.startswith("partial") or "(partly built)" in title.lower():
            state = "partial"
        elif low.startswith(("built", "both built", "the rule holds")):
            state = "built"
        else:
            state = "unstated"            # the record gives no status
        rows.append({"n": int(head.group(1)), "title": title,
                     "state": state, "status": status})
    return rows, (checked.group(1) if checked else "")


def main():
    repos = []
    for repo in REPOS:
        repos.append({
            "name": repo,
            "branch": git(repo, "branch", "--show-current").strip(),
            "commits": commits(repo),
        })
    decided, checked = decisions()
    data = {
        "generated_at": datetime.datetime.now(datetime.timezone.utc)
                        .strftime("%Y-%m-%dT%H:%M:%SZ"),
        "commits_per_repo": COMMITS,
        "repos": repos,
        "uncommitted": {"repo": "vendor/jarvisos",
                        "files": uncommitted("vendor/jarvisos")},
        "decisions_checked": checked,
        "decisions": decided,
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n",
                   encoding="utf-8")
    total = sum(len(r["commits"]) for r in repos)
    print(f"wrote {OUT}: {total} commits, "
          f"{len(data['uncommitted']['files'])} uncommitted paths, "
          f"{len(decided)} decisions")


if __name__ == "__main__":
    main()
