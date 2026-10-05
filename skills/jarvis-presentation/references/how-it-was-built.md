# How the work was done: 2 October 2026

An account of one day on the build server, for the talk. It records who did
what, because the honest version of this story is "one person directing an AI
coding agent", and that is more interesting than pretending otherwise.

## Who did what

**Kevin** owns the project: the idea, the architecture, the 12 earlier
sessions of service code, the choice of device and of LineageOS, renting the
build server. On the day he set direction and made the calls:

- "we obviously want no failures… the more tests the more we know it can work
  on an actual phone" — the decision to test before the phone arrives.
- "your tests are too good to be true" — said when the server tests were all
  green and nothing had been booted. He was right: the first emulator boot
  failed an hour later.
- The alarm idea: an app gives Jarvis tools, Jarvis sets an alarm, and does
  not set it again if it already exists. This became the sample app and the
  end-to-end test.
- Approving commits and pushes, and asking for the test report and this log.

**Claude** (Claude Code, running in a terminal on the build server) did the
hands-on work in one continuous session of about four hours: reading the
code, running the builds, writing the fixes and tests, booting the emulator,
reading the logs, and writing the commits and documents. Concretely:

- Worked out that the "interrupted" build had in fact finished, from the
  build summary and the previous session's transcript.
- Replaced the placeholder ObjectBox classes by running ObjectBox's real code
  generator outside the Android build, and wrote a 36-check database test.
- Repaired the unit test project (29 tests) and fixed the one bug it found.
- Tried Cuttlefish, hit the glibc wall, switched to the Android emulator.
- Built the emulator image (about two hours), then went through four boots:
  service never started → app crashed on every call → errors hidden →
  22 of 22 passing.
- Wrote the sample alarm app, the `cmd jarvis` debug command and the
  end-to-end script.
- Made 13 commits across two repos and pushed them.

## What is worth saying about that on stage

- **The agent's first results looked better than they were.** 65 server
  checks passed before anything had run inside Android. The person, not the
  agent, called that out. The agent's own position at the time was that those
  passes meant "no obvious mistakes in isolation", and it had listed where it
  expected the emulator to break.
- **Its predictions were two for three.** It expected the file watcher to
  fail (it did, silently) and flagged the storage call at start-up as suspect
  (that was the first-boot failure). It expected broadcasts to a never-opened
  app to be blocked; they were not, and the real failure was something it had
  not predicted at all.
- **It also made mistakes.** Its own test script mangled JSON arguments and
  produced 11 false failures. An estimate of 70 GB free disk after the
  emulator build came out at 59 GB. A "stop tracking build output" commit
  only added the ignore file and had to be redone. The first trial run of
  ObjectBox's generator was not sandboxed and may have sent one telemetry
  report.
- **What made it work** was not the agent being right; it was having a real
  system to run against. Every important finding of the day came from a log
  line produced by a booted emulator, not from reading code.

## The day, by the clock

| Time | Event |
|------|-------|
| 10:17 | First compile attempt on the server; service is in the wrong module |
| 11:14 | First full ROM build fails: WorkManager cannot live in system_server |
| 11:37 | Second full build fails at 80%: prebuilt library missing `liblog` |
| 12:06 | Third full build succeeds: first JarvisOS ROM for the phone |
| 12:07 | The vanilla backup turns out to be a hard link and is gone |
| 12:28 | New session. "pick up where we left off" |
| 12:47 | ObjectBox code generated for real; 36 database checks pass |
| 12:53 | Unit tests run for the first time: 28 of 29, then 29 of 29 |
| 12:55 | Cuttlefish will not install; switch to the Android emulator |
| 13:04 | Emulator image build starts |
| 15:09 | Emulator image build finishes |
| 15:17 | Boot 1: Android up, Jarvis service absent |
| 15:21 | Boot 2: service up; app tool calls crash the app |
| 15:48 | Boot 3: replies work; errors hidden, test script wrong |
| 15:52 | Boot 4: 22 of 22; database survives a reboot |
| 16:00 | Everything committed and pushed |
| 16:35 | Phone ROM rebuilt with the emulator fixes (r2) |
| 22:28 | rclone sign-in to Google Drive started on the server |
| 23:25 | Sign-in works; ROM upload rejected by Google rate limit on rclone's shared app |
| 23:35 | Upload abandoned; Kevin to pull the ROM to his PC with `scp` |

## 5 Oct 2026: first look at the emulator, and the big questions

- **Kevin** viewed the emulator from his PC (scrcpy over an SSH tunnel),
  asked for the alarm demo, showed it to Desmond, and then asked the
  direction questions: an interface for Jarvis, voice, why apps talk to each
  other, dynamic UI, future-proof use cases, niche industries. He raised the
  biggest strategic point himself: reliance on Cactus and ObjectBox, "we may
  need to build our own". All of it is recorded in `vision.md`.
- **Claude** wrote the step-by-step instructions for scrcpy, got the emulator
  through setup from the server when the PIN screen went black (entry 20),
  reinstalled the demo app and ran the demo twice (entry 21), checked the
  Cactus source and Hugging Face for what inference would need, measured how
  tied the code is to Cactus and ObjectBox, and wrote up the answers.
- **Claude's slips:** the first demo run went ahead without checking the app
  was still installed, so it printed five errors before the cause was found.

## 5 Oct 2026, evening: the Jarvis app

- **Kevin** asked for "the app for jarvis, as we discussed", then checked in
  once on how long the build would take.
- **Claude** found the proposal in `vision.md` section 2 and built it: the
  signature permission on the service (entry 22), `callTool` and `getStatus`
  on the Binder interfaces and `JarvisManager`, and the app itself
  (`apps/Jarvis`: Chat tab, Tools tab with a form generated from each tool's
  declared parameters). Wrote `tests/emulator/jarvis-app.sh`.
- **Result on the emulator:** 16 of 16 app checks, the older 22 of 22 tool
  checks still pass. An alarm was set from the app's form with no typed
  commands. Chat sends and shows the service's reply, which without a model
  is "I wasn't able to generate a response."
- **Claude's slips:** a build estimate of 25 minutes that took 44 (entry 23);
  a chat layout that hid the conversation behind the keyboard (entry 24); a
  `pkill` pattern that matched its own shell and killed the restart command;
  two faults in its own new test script (it looked for a tool that had
  scrolled off screen, and checked the app's path in a way that broke after
  an update). The older tool-check script also turned out to have a
  race: it failed one check whenever the demo app was already installed,
  because it did not wait for Jarvis to drop the tools after uninstalling.

| Time | Event |
|------|-------|
| 18:26 | "can we do the app for jarvis?" |
| 18:31 | Service changes and app written; emulator image build starts |
| 19:15 | Build finishes (44 min) |
| 19:17 | First run: 15 of 16; chat hidden by the keyboard |
| 19:20 | Chat fixed; 22 of 22 tool checks still pass |
| 19:23 | Tool form fixed; alarm set from the app |
| 19:27 | 16 of 16; image rebuilt with the fixes |
| 19:32 | Rebooted on the new image: 16 of 16 and 22 of 22 with the app built in |
| 20:40 | Kevin: tools should be nested under their apps, as the registry stores them |
| 20:46 | Tools tab rebuilt as an app list that opens to each app's tools (app-only build, 90 s) |

Kevin's point was about the architecture: `AppRecord` owns many `ToolRecord`s,
so the screen should show apps first. Claude's first version listed tools flat
under headings. Still true after the change: the 14 built-in tools all sit
under one made-up app, "JarvisOS System", because the curated tool files say
so; and the permissions in `TOOL_REGISTRY.md` (`ACCESS_RAG_SERVICE` for any
app with user approval, `REGISTER_JARVIS_TOOL` for signed apps) are not what
the code enforces (`ACCESS_JARVIS_SERVICE`, platform-signed only; any app may
register tools). Both are open decisions for Kevin.

## 5 Oct 2026, night: a second engine, and the first answer

- **Kevin** asked what Cactus really offers ("cactus may not give us gguf...
  maybe llama is the best option... or both"), read the findings, forked
  llama.cpp and sent the link. He put tool-reply cards "after November, after
  voice". While it was being built he asked how two engines affect ObjectBox
  and whether a model can cope with many embeddings.
- **Claude** researched Cactus (vision.md section 10), then built llama.cpp
  into the ROM: an `Android.bp` and JNI bridge in the fork (branch
  `jarvisos`), an engine interface in the service with Cactus and llama.cpp
  behind it (`cmd jarvis engine`), a small vector index for llama.cpp, a
  model-install command, 20 new unit tests, and fixes for the bugs in entries
  26 and 28.
- **Result on the emulator:** a real model (Qwen3 1.7B, 1.8 GB, with MiniLM
  for search) answers inside the system service. Plain-English requests set,
  list and delete alarms and read the battery, from the command line and from
  the Jarvis app's Chat tab. Replies take 10 to 26 seconds on the emulator's
  four virtual cores. Unit tests 49 of 49; app checks 19 of 19; tool checks
  22 of 22.
- **Not done / not proven:** nothing on a phone; Cactus itself still untested;
  the flashlight miss (entry 28); documents are not re-indexed when the engine
  changes; llama.cpp's vector index keeps everything in memory, fine for
  thousands of items, not for hundreds of thousands.
- **Claude's slips:** four failed compiles of llama.cpp (entry 25); tried to
  run a downloaded Needle binary on the server and was stopped by the
  session's permission rules; `adb exec-in` hid the output of the first model
  install; in the app, "set an alarm for 8:15" set a real Android alarm
  through the built-in tool rather than the demo app's, which is correct
  behaviour but was not what the check expected.

| Time | Event |
|------|-------|
| 20:55 | Kevin sends the llama.cpp fork |
| 21:06 | First compile attempt |
| 21:25 | Fifth attempt links: `libllama_jarvis.so` |
| 21:29 | Emulator boots with the library loaded in system_server |
| 21:34 | Models installed through `cmd jarvis install-model` |
| 21:35 | First answer: "The capital of France is Paris." |
| 21:36 | First tool call chosen by a model: `demo_set_alarm {"hour":7,"minute":30,"label":"gym"}` |
| 21:40 | Built-in tools searchable; battery question answered |
| 21:45 | Asked and answered from the Jarvis app |
