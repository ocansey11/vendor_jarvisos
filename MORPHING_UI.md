# MORPHING_UI.md — A surface that changes shape

> Plan for discussion. Nothing in this document is built.
> Written 8 October 2026 to the rules of ASD-STE100 (Simplified Technical
> English): short sentences, active voice, one meaning for each term. It was
> not checked with an STE checker.
> The page for the team, with pictures: `docs/morphing-ui.html`.

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
