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
