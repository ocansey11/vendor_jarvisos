# Where Jarvis goes next: vision, use cases and dependencies

Recorded 5 Oct 2026 from a conversation between Kevin and Claude on the build
server, held just after the alarm demo ran on the emulator (and was shown to
Desmond). These are ideas and judgements, not results: nothing in this file
has been built or tested unless it says so. Kevin asked the questions and set
the direction; the answers are Claude's suggestions, and Kevin had not yet
chosen between them.

Use this for the "what's next" and "why does this matter" parts of the talk,
and for audience questions like "who is this for?" and "what if Cactus goes
away?".

## Contents

1. Where things stood on 5 Oct 2026
2. An interface for Jarvis
3. Why apps "talk to each other" through Jarvis
4. Dynamic UI
5. Voice
6. Built-in tools and the most-used apps
7. Future-proof use cases
8. OS changes that make it trustworthy
9. Niche industries
10. The big one: reliance on Cactus and ObjectBox
11. Claims to verify before putting them on a slide

## 1. Where things stood on 5 Oct 2026

- The emulator boots LineageOS 22.2 with the Jarvis service running: database
  open, 14 built-in tools plus 3 from the alarm demo app.
- The alarm demo works end to end when tool calls are typed by hand
  (`cmd jarvis tool ...`): list, set, set again ("already exists. Nothing was
  changed."), and a clear error for a missing argument.
- No AI model has ever run. Cactus is arm64-only, the emulator is x86_64, and
  the code expects `.gguf` files that Cactus v2 does not load.
- Nothing has been flashed to a phone.
- There is no on-screen way to talk to Jarvis. Kevin noticed this while
  showing the demo: "theres no interface to interact with Jarvis now".

## 2. An interface for Jarvis

Proposed: a "Jarvis" system app built into the ROM.

- **Tools screen**: every registered tool, which app owns it, and a button to
  run it. Works on the emulator today, because it needs no model. This is the
  demo-able version of what was shown with typed commands.
- **Chat screen**: text box and conversation, calling the service's existing
  `processQuery`. Says "model not ready" honestly until Cactus works.
- **Prerequisite**: a permission check in `JarvisService`, so only the Jarvis
  app can send queries. Today the binder has no caller check (known gap).

Suggested order: Jarvis app + Tools screen → structured tool replies → Cactus
model fix → chat → voice.

## 3. Why apps "talk to each other" through Jarvis

Kevin asked why apps from different companies would need to talk in real time.

- They already hand work to each other constantly: share sheet, "open in
  Maps", "take a photo" from WhatsApp, payments. The user waits for the result.
- Today the user is the glue (copy address, open Maps, open WhatsApp). Jarvis
  becomes the glue: one request, several tool calls.
- The apps never talk to each other or know about each other. Each answers
  only Jarvis. Jarvis is a switchboard.
- Different companies do not trust each other, so only Jarvis may call tools
  (the protected broadcast; a non-system sender gets
  `SecurityException: Permission Denial`, seen on the emulator 2 Oct 2026).
  Asking the user before risky actions is the next step and is not built.

Slide line: *not apps talking to apps, but every app getting a phone line to
one assistant.*

## 4. Dynamic UI

Three levels, simplest first.

1. **Cards for known answer types.** Tools return data as well as a sentence
   (`{"type":"alarms","items":[...]}`); the Jarvis app has a card per type.
   Buttons on a card are just more tool calls. Predictable, safe, first.
2. **The model arranges the screen** from a fixed set of parts (heading, list,
   button, toggle). The model describes; it never writes code that runs. Every
   button is a tool call, so it goes through the same permission path. Small
   on-phone models (under ~2B parameters) are not reliable at this yet.
3. **Apps send their own UI**, like home-screen widgets. The app owns its look;
   Jarvis hosts it. Best fit for other companies' branding.

Concrete consequence for today's code: `ToolDispatcher` passes back a single
string. Change the reply format to "text plus optional structured data" before
more apps are written against it. The alarm demo would be the first to return
both.

## 5. Voice

"Later in time we would need voice. But baby steps." (Kevin)

- Listening: the service already has `processQueryWithAudio`. Cactus publishes
  speech-to-text bundles (Whisper, Parakeet), so it rides on the Cactus work.
- Speaking: separate problem. LineageOS ships no text-to-speech voice.
- Comes after a text model runs on its own.

## 6. Built-in tools and the most-used apps

The most-used apps worldwide are dominated by Meta and Google (WhatsApp,
YouTube, Chrome, Facebook, Instagram, Gmail, Maps, TikTok, Messenger, Spotify),
from general knowledge rather than live data. Those companies will not add
Jarvis tools soon, and a LineageOS phone has no Google apps by default.

Two practical routes:

- **Built-in tools over Android's standard requests** (set alarm, send SMS,
  call, calendar event). Jarvis already registers 14: `set_alarm`, `send_sms`,
  `make_phone_call`, `create_calendar_event`, `create_contact`, `open_app`,
  `media_control`, `what_is_playing`, `set_volume`, `set_dnd`, `toggle_wifi`,
  `toggle_bluetooth`, `get_battery_status`, `get_notifications`. **None of the
  14 has been run yet**; only the three demo tools are tested. Testing them on
  the emulator needs no model.
- **Notifications** as the bridge to apps that will never cooperate: reading
  "Desmond messaged you on WhatsApp" and replying through the notification's
  own reply action.

## 7. Future-proof use cases

The test for "future-proof": does it rely on something that stays true however
good models get? Jarvis has three things no Play Store app can have:

1. It sees across the whole phone (messages, notifications, files, settings,
   battery, permission history).
2. It runs on the device; data need not leave.
3. It enforces the rules between apps.

Use cases built on those age well. Use cases built on "the model is clever"
get overtaken by the next model.

| User complaint | What Jarvis does | Depends on |
|---|---|---|
| "I know I saw it somewhere" | Search everything at once: files, messages, notifications, text in screenshots | File watching, which does not work yet |
| "My phone interrupts me constantly" | "Only my mum, the bank and work until 6", plus a catch-up summary | OS sees every notification |
| "My phone is confusing" | "Why is my battery dying?" answered from real usage data; settings changed by sentence | OS-only data and settings access |
| "Is this a scam?" | On-device scam text/call warnings; "which apps used my microphone this week?" | Android's permission-usage history |
| "I'm the glue between apps" | Multi-step requests with confirmation | A good model and app tools; do last |
| "It doesn't know me" | Facts you told it, on the phone, visible and deletable; overnight prep via DreamWorker | Scheduler, which has never been seen firing |

Rule for anything proactive: quiet unless asked. Unwanted suggestions are how
assistants get switched off.

## 8. OS changes that make it trustworthy

1. Risk levels on every tool; risky ones need a tap to confirm, on a screen
   apps cannot fake.
2. A log of everything Jarvis did, with undo where possible.
3. Visible controls over what Jarvis indexes and remembers, with "forget this".
4. Swappable models (see section 10).
5. A fixed test set of requests ("wake me at 7:30" → `set_alarm` with 07:30),
   run against every new model, like code tests.
6. Line the tool format up with emerging standards (Google's App Functions on
   Android, MCP) so tools written for other assistants work with Jarvis.

Avoid: screen-reading "tap the buttons for me" automation as a main path. It
breaks on redesigns and is risky in banking apps. Last resort, with
confirmation.

Suggested long-term order: trust layer → "find it" → notification calm →
phone helper → scam and privacy checks → multi-step tasks.

## 9. Niche industries

Kevin: "outside being a glorified PA, what niche industry can this OS fit in
naturally". Jarvis as an assistant that works offline, keeps data on the
device and controls the whole phone fits where those are requirements.

Strongest fits:

1. **Frontline and field workers on company phones** (delivery, warehouse,
   utilities, field service). Hands busy, poor signal, and companies already
   buy custom-Android devices (Zebra, Honeywell). Tools are the company's own
   apps, the pattern the alarm demo proved.
2. **Home care and community health workers.** Paperwork burden; sensitive
   patient data makes on-device a selling point. Paperwork and recall only,
   never medical advice, or it becomes a regulated medical device.
3. **Phones for older people, run by their families.** Scam protection,
   simple voice use, remote setup. Existing "senior phones" are locked-down
   launchers with no intelligence.

Harder: accessibility (high impact, small market), privacy-critical
professions (journalists, lawyers), government and defence (years of
certification).

Claude's pick: **home care workers**. Frontline is the bigger market; older
people is the best consumer story. Suggested next step costs nothing
technical: interview five to ten people in the chosen niche.

Business customers will ask first about: licences (section 10), managing
fleets of devices, years of monthly security updates, and rugged hardware.

## 10. The big one: reliance on Cactus and ObjectBox

Kevin, 5 Oct 2026: *"one big thing to note is the idea of reliance on cactus
and objectbox. we may need to build our own."*

### Why it matters

- **Cactus** is proprietary. Free only below $2M revenue **and** $2M funding;
  exceeding either ends the grant with 30 days to buy a commercial licence.
  It is arm64-only (built with `-march=armv8.2-a+...+i8mm`), which is why
  nothing can be tested on the emulator. It changes fast: v2 dropped the
  `.gguf` files Jarvis was written for.
- **ObjectBox** is also proprietary underneath. Its Java bindings are Apache
  2.0, but the native library inside the system image is under a closed
  "Binary License" whose terms for bundling in an OS are not published.
  F-Droid has rejected apps for depending on it.
- For a privacy OS, and for any business customer's lawyers, both are
  questions that must have answers before a public release.

### How tied in the code is today (measured 5 Oct 2026)

- Cactus: one entry point, `CactusWrapper`, as the architecture rules require.
  But 12 files call it directly and depend on its idea of model and index
  handles.
- ObjectBox: 14 entity/model files carry ObjectBox annotations, and 8 files
  outside the store use `Box`/queries directly (`ToolDispatcher`,
  `SystemToolExecutor`, `ToolScannerService`, `MetadataSearch`, `DreamWorker`,
  `JarvisIndexWorker`, `JarvisService`, `JarvisStore`). The storage plan says
  ObjectBox should sit behind one data-access layer; it does not yet.

### What "build our own" should mean

Claude's view: own the *interfaces*, not necessarily the engines.

- **Storage: no need to write a database.** SQLite is already in Android and
  fully open. Room (or plain SQLite) for metadata plus
  [sqlite-vec](https://github.com/asg017/sqlite-vec) for vectors gives a fully
  open storage layer. "Our own" here is our own data-access layer that
  everything goes through, so the engine underneath is replaceable. Moderate
  effort; the 8 files above are the work list.
- **Inference: writing an engine from scratch is a multi-year, multi-person
  job** (hand-tuned maths for each CPU, quantisation, and new model
  architectures every month). The realistic version is an `InferenceEngine`
  interface inside Jarvis with Cactus as one implementation, and an open
  engine as the alternative. Candidates to evaluate: llama.cpp (MIT), Meta's
  ExecuTorch (BSD), MLC LLM (Apache 2.0), Google's on-device LLM runtime.
  llama.cpp in particular also runs on x86_64, so inference could finally be
  tested on the emulator, and it uses `.gguf`, the format the code originally
  assumed.
- Owning the interfaces means a later swap is a contained job, the licence
  question has an exit, and different engines can be compared on the same
  test set (section 8, item 5).

### When to do it

The storage-layer decision of 2 Oct 2026 still applies: **do not migrate
before the first phone test.** A migration now costs the phone test and buys
nothing the demo needs. What is cheap now and keeps the exit open:

1. Stop new code from using ObjectBox or Cactus types outside the storage and
   inference layers.
2. Introduce the `InferenceEngine` and storage interfaces when those files are
   next touched for other reasons (for example, the model-path fix).

Triggers to actually switch: approaching the $2M revenue or funding line;
wanting F-Droid or a business customer whose lawyers ask; Cactus breaking
Jarvis again on upgrade; or needing inference testable off-phone.

### Proposal on the table: llama.cpp as a second engine (undecided)

Kevin asked, 5 Oct 2026: "should we setup an option for lamma.cpp then ? and
build ? and at any point we can switch from either". Claude proposed:

- An engine interface inside Jarvis with two implementations, Cactus (as now)
  and llama.cpp, chosen by a setting (`auto`, `cactus`, `llama`) and
  switchable at runtime with `cmd jarvis engine`.
- llama.cpp built into the ROM from a fork under `ocansey11`, for both arm64
  (phone) and x86_64 (emulator), so a real model could be tested on the
  emulator before the phone arrives.
- Glue code that returns Cactus's reply shape, so the rest of Jarvis does not
  care which engine answered.
- Catches: search indexes are tied to an embedding model, so switching engine
  means re-indexing; the emulator needs the host CPU passed through for AVX2.
- Cost: about a day of work, two emulator rebuilds; Cactus untouched.

Kevin's answer: "ill come back to this, its a big decision." Nothing started.
For the talk, this is the moment the dependency question stopped being
abstract.

Slide line: *the dependency you don't own is the roadmap you don't control.*

## 11. Claims to verify before putting them on a slide

- App rankings in section 6: general knowledge, varies by country and year.
- Google App Functions and MCP status: check what has shipped and where both
  stand at the time of the talk.
- Licence details for llama.cpp, ExecuTorch, MLC LLM, and the current Cactus
  and ObjectBox terms: re-read the licence files themselves.
- AWS Graviton3 having i8mm and the Hetzner CAX / Graviton2 lacking it: from
  general knowledge, check before renting.
- Market claims in section 9 are judgement, not research.
