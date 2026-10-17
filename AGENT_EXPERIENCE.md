# AGENT_EXPERIENCE.md — Agent Experience (AX)

> Plan for discussion. Built: the tools, and since 17 October 2026 the first
> slice of sessions (section 11, emulator only). Views (sections 1 to 10) are
> not built.
> Written 8 October 2026 (as MORPHING_UI.md) and 17 October 2026, to the rules
> of ASD-STE100 (Simplified Technical English): short sentences, active voice,
> one meaning for each term. It was not checked with an STE checker.
> The page for the team, with pictures: `docs/agent-experience.html`.

An app on JarvisOS has two users: the person and Jarvis. AX is how an app
works with Jarvis. AX has three parts (section 11.1): tools (built), views
(sections 1 to 10, "a surface that changes shape") and sessions (section 11).

## 1. Purpose

Today Jarvis gives each reply as one line of text. This plan gives the reply a
shape that fits its content. It also gives the work to the correct owner: the
app makes the view, Jarvis shows the view, and the model only selects the tool.

The team must make one decision: who makes the view (section 4).

## 2. Terms

Each term has one meaning in this document.

| Term | Meaning |
|------|---------|
| Tool | An action that an app gives to Jarvis. Example: `set_alarm`. |
| Reply | The result that a tool sends to Jarvis. |
| Surface | The area of the screen where Jarvis shows a reply. |
| Shape | The size and layout of the surface. There are five shapes. |
| View | A description of the content of a card. A view is data. A view is not code. |
| Part | One element of a view. Examples: list, button, toggle. |
| Catalog | The fixed list of parts that Jarvis can draw. |
| Bar | The lowest confidence at which Jarvis runs a tool without a question. Today the bar is 0.75. |

## 3. The five shapes

The Jarvis app selects the shape with fixed rules. A model does not select the
shape.

| Shape | Jarvis uses it when | Example |
|-------|---------------------|---------|
| Pill | Jarvis listens. | A small bar with the words that Jarvis hears. |
| Line | A tool ran and the reply has text only. | "Flashlight on" |
| Chips | Needle named a tool, but its confidence is below the bar. | "Set alarm 07:30" and "Ask Jarvis" |
| Card | The reply has data and the tool has a view. | A list of alarms with a toggle for each alarm. |
| Sheet | The tool needs a confirmation or an argument is missing. | "Send this message to Sam?" |

An answer from the chat model uses the Card shape with text only.

The Chips shape is new. Today a request below the bar goes to the chat model.
On the emulator that path takes 21 to 23 seconds. With chips, one touch runs
the tool. This is a hypothesis. Work package 3 tests it.

## 4. Who makes the view

| Option | Who makes the view | When | Model work | Speed | Brand of the app |
|--------|--------------------|------|------------|-------|------------------|
| A. Built-in views | Jarvis | At build time of the ROM | None | Fast | None |
| B. App-declared views | The app, from the catalog | At build time of the app | None | Fast | Icon and one colour |
| C. App-hosted surface | The app draws its own pixels | At run time | None | One more process | Full |
| D. Model-composed views | The model, from the catalog | At run time | Large | Slow on a phone | None |

Recommendation: make option B the primary path.

- The app knows its data. Thus the app is the correct owner of the view.
- The view is a file in the APK. Jarvis reads it at install time, as it reads
  the tool definition. No model reads or writes it.
- The fast path stays fast. On the emulator, a spoken request becomes a tool
  call in approximately 250 ms. Option B adds only the time to draw.
- Option A is option B for the built-in tools. Use the same catalog for both.
- Keep option C for apps that must show their own design. Do a test first
  (work package 5).
- Keep option D for a view that mixes data from two apps. Do not start it
  before a larger model runs on the phone.

## 5. The contract

### 5.1 The reply

A tool sends text and, optionally, data. Tools that send only a string
continue to operate.

```json
{
  "text": "You have 2 alarms.",
  "data": {
    "alarms": [
      {"id": 3, "time": "07:30", "label": "Gym", "enabled": true},
      {"id": 4, "time": "08:15", "label": "",    "enabled": false}
    ]
  }
}
```

### 5.2 The view

The app declares the view with the tool, in its manifest
(`com.jarvisos.tool.view`). The view binds parts to the data with paths.

```json
{
  "view": "list",
  "title": "Alarms",
  "items": "$.alarms",
  "item": {
    "primary": "$.time",
    "secondary": "$.label",
    "toggle": {
      "value": "$.enabled",
      "tool": "demo_set_alarm_enabled",
      "args": {"id": "$.id", "enabled": "$toggle"}
    }
  },
  "actions": [
    {"label": "New alarm", "tool": "demo_set_alarm", "ask": ["hour", "minute"]}
  ]
}
```

### 5.3 The actions

Each button, toggle or chip is a tool call. Jarvis sends it through
`ToolDispatcher`. Thus each action obeys the same permission and the same
confirmation rule as a spoken request.

### 5.4 Catalog, version 1

Twelve parts: text, heading, row, column, list, item, icon, button, toggle,
slider, chip, field.

## 6. Safety rules

1. A view is data. Jarvis does not run code from a view.
2. Jarvis checks each view against the catalog when the app is installed.
   Jarvis refuses a view that is not correct.
3. Text from an app is plain text. Jarvis does not read it as layout or as an
   instruction.
4. An action in a view can call only the tools of the same app. An action
   that calls a tool of a different app needs a confirmation from the user.
5. A view cannot load content from the network.
6. An app can set its icon and one colour. Jarvis sets all other style.
7. Jarvis limits the size of a reply and the number of items in a list.

## 7. What others do

| System | Owner | What it is | Use for Jarvis |
|--------|-------|-----------|----------------|
| A2UI 0.9 (April 2026) | Google | A model sends a description of a view. The client draws it from its own catalog. | The catalog idea and the JSON form. It has no Android Compose renderer in its list. |
| MCP Apps (spec 2026-01-26) | Anthropic and OpenAI | A tool declares a UI resource before the call. The host shows it in a sandbox. | The same idea as option B: the view is declared with the tool. It uses HTML, which is too heavy for the system surface. |
| AppFunctions (Android 16) | Google | Apps give functions to an agent on the device. | Tools only. It has no view. Jarvis has the same idea in `TOOL_REGISTRY.md`. |
| App Intents snippets (iOS 26) | Apple | The app gives a view for the result. The system shows it. The view can have buttons. | The best example of "the app does the work". It is option C. |
| RemoteViews, Glance, Remote Compose | Google | Methods for one process to draw a view that another process made. | Candidates for option C. |
| AG-UI | CopilotKit | A stream of events between an agent and a screen. | Useful later, when a reply arrives in steps. |

No system in this table was tested for this plan. Do a check of each
statement before it goes on a slide.

## 8. Work packages

The sequence obeys Kevin's priority: model, then voice, then cards. No work
package starts before the first test on a phone.

| No. | Work | Pass criterion |
|-----|------|----------------|
| 1 | Reply with text and data. Change `ToolDispatcher` and add one AIDL method. | The alarm demo sends both. The 22 tool checks continue to pass. |
| 2 | The five shapes in the Jarvis app, with built-in views (option A). | A test shows each shape. Jarvis draws a card in less than 100 ms after the reply. |
| 3 | Chips for requests below the bar. | On 100 labelled requests, the correct tool is the first chip in a minimum of 90. |
| 4 | App-declared views (option B): catalog, check at install, alarm demo. | Jarvis refuses a bad view. Jarvis refuses an action that calls a different app. |
| 5 | App-hosted surface (option C): a test of RemoteViews and Remote Compose. | A report with the draw time and the memory for each. A decision. |
| 6 | Model-composed views (option D). | Research only. |

## 9. Questions for the team

1. Is twelve parts the correct size for the catalog? Which part is missing?
2. Does Jarvis use the A2UI JSON form, or a smaller form of its own?
3. Where is the surface: in the Jarvis app, or above all apps as a system
   layer?
4. Can an app change its view at run time, or only at install time?
5. How much of its brand can an app show?
6. What must the surface do for a user who cannot see the screen?
7. Apps can also give typed data to each other through Jarvis (a contact, a
   place, a time). Is that the same catalog idea, applied to data? This is a
   separate topic for a later plan.

## 10. Sources

- A2UI 0.9: https://developers.googleblog.com/en/a2ui-v0-9-generative-ui/
- MCP Apps (SEP-1865): https://modelcontextprotocol.io/seps/1865-mcp-apps-interactive-user-interfaces-for-mcp
- AppFunctions: https://developer.android.com/ai/appfunctions
- App Intents snippets: https://developer.apple.com/documentation/appintents/snippetintent
- Glance and Remote Compose: https://developer.android.com/jetpack/androidx/releases/glance-wear
- Jarvis measurements: `TESTING.md` and `tests/emulator/voice.sh`, 8 October 2026, emulator only.

## 11. Agent Experience (AX)

> Added 17 October 2026 from a discussion with Kevin. Use the name "Agent
> Experience (AX)", not "Agentic Interaction": the short form of that name is
> "AI", which is confusing in a talk about AI.
>
> **Status, 17 October 2026 (evening):** the first slice is built and seen
> working on the emulator, with synthetic taps, not on a phone and not by a
> person: the tic-tac-toe game (section 11.9) is played to the end against
> Jarvis, started from the app and from Jarvis. Each subsection says what is
> built. Section 11.11 lists what is different from the plan and why;
> section 11.12 lists what is not built.

### 11.1 The idea

An app on JarvisOS has two users: the person and Jarvis. UX is how an app
works with the person. AX is how an app works with Jarvis.

AX has three parts:

| Part | Direction | What it is | Where |
|------|-----------|-----------|-------|
| Tools | App to Jarvis | Actions that Jarvis can do in the app. | Built. `TOOL_REGISTRY.md`. |
| Views | Jarvis to person | The shape of a reply on the surface. | Sections 1 to 10 of this plan. |
| Sessions | App and Jarvis, live | Jarvis takes part in the app while it runs. Example: a game with turns. | This section. First slice built, 17 Oct 2026. |

### 11.2 New terms

| Term | Meaning |
|------|---------|
| Session | A period in which Jarvis takes part in one app. The session has a start and an end. |
| Session manifest | The file in the APK that describes the session. Jarvis reads it at install time. |
| Event | Data that the app sends to Jarvis during a session. Example: `your_turn`. An event is data. An event is not a prompt. |
| Session tools | The tools that are open only during the session. |
| Start tool | A tool that opens the app and starts a session. Jarvis uses it to find the app. |

### 11.3 The session manifest (static)

The developer writes the session manifest one time. The same file goes to all
users. Thus the file describes the app for any agent and any person. It does
not name a user. It does not contain data about a user.

```xml
<!-- AndroidManifest.xml, next to the tool declarations -->
<meta-data android:name="com.jarvisos.session" android:resource="@xml/jarvis_session" />
```

```xml
<!-- res/xml/jarvis_session.xml -->
<session name="tic_tac_toe" title="Tic-tac-toe" start-tool="start_tic_tac_toe">
    <rules>Players take turns on cells 0-8. Three in a row wins.
           House rule: cell 4 is lava. Nobody can play it.</rules>
    <persona>Cheeky, competitive, one-liners. Never mean.</persona>
    <tools>get_state, evaluate_moves, move</tools>
    <events>player_moved, your_turn, player_said, game_over</events>
    <turn event="your_turn" tool="move" arg="cell" choices="legal" />
</session>
```

**Built.** `SessionManifest` reads this file when the app is installed (and
at boot), next to the tools, in `SessionRegistry` (in memory; every app is
read again at boot). The sample's real file is
`apps/JarvisTicTacToe/res/xml/jarvis_session.xml`. Jarvis refuses a manifest
as a whole when it breaks a rule: rules over 1,200 characters, persona over
300, more than 8 tools or 12 events, names that are not plain identifiers,
`say` in the tool list (Jarvis gives it), or a `<turn>` that uses an
undeclared event or tool.

`<turn>` is new (section 11.11): it tells Jarvis which event wakes it, which
tool call ends its turn, and which field of the event lists the legal values
of that tool's argument.

The session tools are ordinary tool receivers (as in the alarm demo). The
difference: they are closed outside the session, and models are not offered
them.

### 11.4 Events (live)

```java
JarvisSession s = JarvisSession.start(context, "tic_tac_toe", closedTools, listener);
s.event("player_moved", "{\"cell\":0,\"board\":\"X........\"}");
s.event("your_turn",    "{\"board\":\"X........\",\"legal\":[1,2,3,5,6,7,8]}");
s.end("game_over",      "{\"winner\":\"player\"}");
```

**Built** as `android.jarvis.JarvisSession` (`@hide`), over a new Binder
service `jarvis_session` (`IJarvisSessionService`, `IJarvisSessionCallback`).
It needs no permission: any app can find it (SELinux type
`jarvis_session_service`, an `app_api_service`), but Jarvis checks that the
caller's package declares the session and that the app is on screen. The
listener gets `onTurnStarted`, `onSay`, `onTurnEnded(done, detail)` and
`onEnded(reason)` on the main thread. The app may close session tools it
declared (`closedTools`), never open others; this is how the game's
evaluate_moves switch works.

Limits: an event must be one the manifest lists; its data must be a JSON
object of at most 2,048 characters; at most 10 events at once and then 2 a
second (`EventLimiter`).

**Built: Jarvis's memory in the prompt.** Jarvis reads its facts about the
user from `UserContext` (written by `DreamWorker`). On the emulator there are
none, because `DreamWorker` has never been seen running (see the known
gaps). The prompt then says so: "Nothing is stored about the user yet." It
does not invent facts. Personalisation is therefore built but unproven.

The app does not send prompts. Jarvis makes the prompt for each turn from
these parts:

| Part | Source | Same for all users? |
|------|--------|---------------------|
| Rules and persona | Session manifest (developer) | Yes |
| Facts about the user | Jarvis memory, on the device | No |
| The event | The app, now | No |
| The open tools | Tool registry | Yes |
| Recent events | The session, last few only (context is approx. 4,000 tokens) | No |

Reasons:

1. Jarvis owns its prompt. The app does not need to know the model. A change
   of model (Qwen, Gemma) does not break the app.
2. The manifest is fixed at install time. It can be examined. An event
   arrives at run time and can change at any time.
3. An app that sends prompts can try to control Jarvis.
4. The app never gets data about the user. Only Jarvis combines the game
   and the facts about the user, on the device.

### 11.5 Safety rules for sessions

1. Text in the rules and in events such as `player_said` still goes to the
   model. A small model can obey that text. Thus "data, not prompts" is not
   sufficient protection.
2. The primary protection is the tool fence. During a session, Jarvis can use
   only the session tools and `say`. If an app controls the model fully, the
   worst result is a bad move in the game.
3. Jarvis limits the size of each event and the number of events per second.
4. A session ends when the app goes to the background or stops. Then the
   session tools close.

**Built.** The fence is in `ToolDispatcher`, on the one route every tool
call takes (agent loop, Needle fast path, session turn, `cmd jarvis tool`):

- During a session: only `say` and the session's open tools run, and a tool
  name is looked up in the session's app only (two games can both have
  `move`). Anything else gets "Error: refused. During the Tic-tac-toe
  session Jarvis can only use say, get_state, evaluate_moves, move; ...".
- Outside a session: session tools are refused, and they are left out of
  the tool lists the chat model and Needle see.
- The session ends when the app's uid leaves the foreground (an
  `ActivityManager` importance listener with "visible" as the cut), when its
  callback Binder dies, when the app ends it, or when another session
  starts. One session at a time.

Seen on the emulator (`tests/emulator/tictactoe.sh`): `set_alarm` and
`open_app` are refused during a game; `move` and `get_state` are refused
after the Home button ended it.

Still true, and not solved: the rules and event data are text the model
reads (rule 1).

### 11.6 How a session starts

There are two ways. Both use the same session.

| Start | Sequence |
|-------|----------|
| From the app | The person touches "Play with Jarvis". The app calls `startSession`. A button stands in for the wake word "Hey Jarvis" until wake word detection exists. |
| From Jarvis | The person says "Hey Jarvis, let's play tic-tac-toe". Jarvis finds the start tool in the registry. The start tool opens the app. The app calls `startSession` when it is ready. If the app does not do this in a set time, Jarvis tells the person. |

**Built, both.** From Jarvis: the start tool is an ordinary tool, so the
Needle fast path or the chat model can choose it. Jarvis sends its call with
permission to start an activity from the background (a start tool's app is
usually not on screen), then waits up to 15 s for the app's `startSession`.
The reply says which: "... The Tic-tac-toe session with Jarvis is open." or
"... did not start a session with Jarvis within 15 seconds." On the
emulator, `cmd jarvis query "let's play tic-tac-toe"` went through Needle
and answered in about 1 s with the session open. Only typed, not spoken.

### 11.7 More than one match: Jarvis asks

If two apps have a start tool that matches ("let's play chess" with two chess
apps installed), Jarvis does not select one. Jarvis asks the person.

This is a general rule, not a rule for games only:

- Two apps match one request.
- Needle returns two tools with near confidence, or one tool below the bar.
- A tool needs an argument that the request does not give.

The surface already has shapes for this: Chips (section 3) for a choice,
Sheet for a missing argument. The exact rule (how near is "near", how many
choices, voice-only use) is not designed yet. Kevin: "we will sort this out
later".

**Not built (on purpose).** Two hooks mark the place: a `TODO(AX 11.7)` in
`ToolDispatcher.findAndDispatch` (today the first registered tool of a name
wins) and a warning in `SessionRegistry` when two apps declare the same
start tool.

### 11.8 Turns

1. The `your_turn` event wakes Jarvis. Jarvis does one turn and stops. It does
   not wait in a loop. Thus each turn is a short task, and the five-turn limit
   in `JarvisExecutor` is not a problem.
2. Needle does not process turns. The event tells Jarvis what to do, so no
   routing is necessary. The chat model does the turn.
3. The tools do the calculation. The model decides. Example: `evaluate_moves`
   gives the result of each legal move. Jarvis selects, and can use facts
   about the person.
4. llama.cpp can limit the output to a grammar. Then the model can only call
   `move` with a legal cell. Not checked: does our JNI layer pass a grammar?
5. If the model calls a wrong tool, Jarvis tells it the legal moves and tries
   again, one or two times. Then the app makes a random legal move.
6. A turn takes approx. 20 seconds with Qwen3-1.7B on the emulator. The app
   shows "Jarvis is thinking".

**Built** (`session/SessionTurn.java`), with these results on the emulator:

- The turn runs on its own thread; `sendEvent` returns at once. The chat
  model is called with only `say` and the open session tools, and with the
  turn tool's argument narrowed to the event's legal values (a JSON-schema
  `enum`).
- **Grammar: yes, it was small to add.** Our JNI passed no options before;
  it now takes `tool_choice`, `parallel_tool_calls`, `constrain_tools` (use
  the grammar llama.cpp builds from the tool schemas), `calls_only` (start
  the reply with the template's tool-call marker) and a raw `grammar`. With
  the grammar on, a `move` call cannot carry a cell outside the legal list.
  "Required" alone did not stop the model writing words first (roadblock
  37); `calls_only` does. `cmd jarvis session grammar on|off` switches it,
  for comparison.
- Retries: a refused tool, a failed `move`, or a reply with no call counts
  as wrong. After a wrong one the model is told the legal values and given
  only the turn tool. After 3 wrong ones (or 4 model calls) Jarvis gives up
  and the app plays a random legal move. The game also moves at random if a
  turn takes over 120 s.
- If the turn ended without a word, one more short call that may only use
  `say` gives the person a line about the move.
- The JNI now keeps the last prompt in the model's cache and reuses the
  shared beginning: a turn's second call reuses about 760 of 850 tokens.
- Times on the emulator (Qwen3-1.7B Q8_0, 4 threads, x86_64 `-cpu host`):
  see section 11.9 and `TESTING.md`.

### 11.9 First sample: tic-tac-toe, Kevin against Jarvis

| Tool | Gives |
|------|-------|
| `start_tic_tac_toe` | Opens the game. Start tool. |
| `get_state` | `{"board": "...", "legal": [...], "kevin_last_move": 4}` |
| `evaluate_moves` | `{"0": "you lose in 2", "5": "blocks Kevin", "7": "wins"}` |
| `move(cell)` | Plays a cell. |
| `say(text)` | Jarvis talks during the game. It can use facts about the person. |

Experiment for the talk: play with `evaluate_moves` off, then on. Same model,
same game. This shows how much of the result comes from the tools.

This sample is also the example for other developers: declare the session,
send events, give tools.

**Built:** `apps/JarvisTicTacToe` (package `com.jarvisos.tictactoe`), in the
ROM as an ordinary, non-platform-signed app (it runs as `untrusted_app`; it
is in `/system` only because `JarvisSession` is `@hide`). The AX parts are
in `Game.java` and the manifest, with comments. Differences from the table
above: `get_state` says `player_last_move`, not `kevin_last_move` (the app
never names a user); `evaluate_moves` returns
`{"best":[4],"cells":{"0":"loses: player wins in 3 moves","4":"draw",...}}`
(roadblock 38: the model misread the table without `best`); the
evaluate_moves switch is on the game screen and applies to the next game.

### 11.10 Open questions

Answered by the first slice, for now (Kevin may change them):

- Question 1: XML, like the tool declarations.
- Question 4: no. One session at a time; a new one ends the old one.
- Question 5: during a session the fence refuses every other tool, also for
  requests typed into the Jarvis app. Jarvis can still answer in words.

1. Is the session manifest XML (like the tool declarations) or JSON (like the
   views)?
2. How near must two confidences be before Jarvis asks?
3. Can Jarvis start a session in an app with no "Play with Jarvis" button?
4. Can two sessions be open at the same time?
5. What does Jarvis say when the person speaks to it about something else
   during a session?
6. Where does AX go in the sequence of section 8? Not decided.

### 11.11 Where the build differs from the plan, and why

| Plan | Built | Why |
|------|-------|-----|
| Manifest has rules, persona, tools, events | Also `<turn event tool arg choices>` | Jarvis must know which event wakes it, which call ends a turn, and what the legal values are, without a name like "your_turn" or "move" in Jarvis's code. |
| `jarvis.startSession(...)` on JarvisManager | `JarvisSession.start(...)`, own Binder service `jarvis_session` | `jarvis` needs a signature permission that a game cannot hold. Sessions need none: the service checks the package and the foreground instead. |
| `say` is a game tool | `say` is given by Jarvis and delivered through the session callback | Every session gets it the same way, and an app cannot declare a tool of that name. |
| — | The app may close declared tools at start (`closedTools`) | The evaluate_moves switch for the talk's experiment. |
| — | After a silent move, a say-only call | With only tool calls allowed, the model rarely spoke (roadblock 37). |
| "The model decides" from the tool's table | The tool also names the best cells | Qwen3-1.7B misread the table (roadblock 38). |

### 11.12 Not built yet

- Two apps match: Jarvis asks (section 11.7). Hooks only.
- The views of sections 1 to 10, and a card for Jarvis's line: the game
  shows `say` in its own bubble.
- Voice: the start request was typed (`cmd jarvis query`); a turn's line is
  shown, not spoken.
- `player_said` and any other free-text event; the sample sends only data.
- Facts about the user: wired, but there are none on the emulator.
- Persistence: sessions are in memory; a reboot ends them. The session
  manifests are read again at boot.
- An SDK for apps outside the ROM: today an app must be built in the tree
  with `platform_apis` and installed in `/system` to use the `@hide` API.
- Nothing has run on a phone.


## 12. The tool contract: action, values, state

> Added 17 October 2026 from a discussion with Kevin. Not built.

### 12.1 The idea

In test mode, the Jarvis app shows the real state next to each built-in tool:
battery 33 %, flashlight on, brightness 40 %. Thus a person can see what a
tool did, not only that it replied "done". Today this works only for the
built-in tools, because `DeviceState.java` in the Jarvis app has code for each
of them. A tool from a different app shows only its name.

This section makes state part of each tool. Each tool, from each app, declares
three things:

| Part | Meaning | Examples |
|------|---------|----------|
| Action | What the tool does. | "Plays a cell." "Turns the torch on or off." |
| Values | Each argument, with its type and its permitted values. | `cell` integer 0 to 8; `level` 0 to 100 %; `on` true or false |
| State | What the tool controls looks like now. The app reads it. Jarvis does not guess it. | battery 33 %; torch on; `evaluate_moves` off; board `X...O....` |

Then Jarvis shows each tool of each app in the same way, with no code for one
app in Jarvis. A developer gets this when the developer declares the values
and the state.

### 12.2 Values

Each argument declares its type and its range or its list of values in a form
that a program can read, not only in the description:

```xml
<param name="cell" type="integer" min="0" max="8" choices-from="legal" />
<param name="level" type="integer" min="0" max="100" unit="%" />
<param name="on" type="boolean" />
```

`choices-from` names a field in the state. It gives the values that are
permitted now (example: the empty cells). The grammar of the model (11.8) and
the value picker in test mode use the same list.

### 12.3 State

- Each app can declare one read-only state tool. Example: `get_state`.
- Each tool names the part of that state that it controls. Example:
  `evaluate_moves` controls `tools.evaluate_moves` (on or off).
- Jarvis reads the state when it needs it (pull). Session events (section 11)
  continue to push the moments that matter.
- The built-in tools use the same contract. `DeviceState` becomes the state
  tool of the built-in tools.

### 12.4 Uses

1. **Test mode.** The Tools tab shows each tool with its values and its state,
   for each app. A person can run a tool with a picker that offers only the
   permitted values. For a game, this is "play the side of Jarvis by hand".
   This tests the tool before a model uses it.
2. **The model checks its work.** After a call, Jarvis reads the state again.
   Example: torch on → is the torch on? Roadblock 34 was a tool that replied
   "success" and changed nothing.
3. **Sessions.** The session panel in test mode shows: session open or
   closed, the open tools, the permitted values, the last call and its time.

### 12.5 Open questions

1. Pull only, or can an app also push a change of state outside a session?
2. Does a run from test mode obey the tool fence of a session? Proposal: yes,
   so that the test shows real behaviour.
3. How often does test mode read the state? Proposal: when the screen opens,
   after each call, and on a refresh button.
