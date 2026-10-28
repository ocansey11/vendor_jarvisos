# JarvisOS build server

You are in `/srv/jarvis/lineage`, the JarvisOS tree (LineageOS 22.2 with an
on-device AI service in `system_server`). The server is shared: Kevin (root),
Sam and Desmond, group `jarvis`. Kevin owns the project and decides every push.

- Start with `jh status`, and read the `jarvis-harness` skill before building,
  booting or testing anything. `jh read overview` lists where everything lives.
- One `out/`, one build at a time, one emulator for everyone. `jh` enforces
  this; don't go around it (no raw `m`, `emulator`, or second builds).
- Your own repos' branches are shared working trees: commit small, say what you
  changed, and don't rewrite history or reset someone else's work.
- Commit under your own name (`git config --global user.name/user.email`).
  A hook adds `Server-user: <account>` to every commit; don't remove it or
  skip hooks (`--no-verify`). Kevin reads it before he pushes.
- Docs: `ARCHITECTURE.md` (service internals), `AGENT_EXPERIENCE.md`
  (sessions), `TESTING.md`. Update them when you change what they describe.

## Leave a reading list for Kevin

Kevin reads the code to understand what was built. Whenever you build a new
app on the emulator, or change how a feature works across several files, add
or update a reading list in `vendor/jarvisos/harness/reading/NAME.list`
(see `tictactoe.list`):

- the files in the order things happen at run time: what the app declares,
  the app, the public API, the service, the model, what is kept afterwards,
  then the design doc and the test;
- one line per file on what to look for, naming a method or a search word;
- `title:` says what the list follows, `by:` says whose Claude and the date.

`jh read --check` must pass. Commit the list with the change and name it in
your summary ("read it with `jh read NAME`").
