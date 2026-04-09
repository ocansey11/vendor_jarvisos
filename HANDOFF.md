# JarvisOS Handoff — Session 12 (Apr 2026)

## Code state

| Repo | Branch | Last commit | Status |
|------|--------|-------------|--------|
| frameworks/base | lineage-21.0 | af1a9a86 | ✅ pushed |
| vendor/jarvisos | main | f7f8bb3 | ✅ pushed |
| vendor/cactus | main | a52d69f | ✅ unchanged |

---

## What was built this session

### ModelRegistry — "primary" chat model entry
- `ModelEntry` gains `chatOnly` flag. `isReady()` only requires `modelHandle` for chat-only entries.
- `registerChatModel(name, modelPath)` — new method, skips `indexInit` (chat models don't need a vector index).
- `destroy()` skips `indexDestroy` for chat-only entries.
- `JarvisService` registers `"primary"` at `GEMMA4_MODEL_PATH = /data/system/jarvis/models/gemma4.gguf`.
  PlanNode + RespondNode + DreamWorker already prefer `"primary"`, fall back to `"rag"`.
  Will activate automatically once Sam drops the GGUF at that path.

### Curated tool loader — ToolScannerService
- `loadCuratedTools()` reads `/system/etc/jarvisos/tools/*.json` at boot.
- Upserts as `sourceType="curated"` ToolRecords — same ObjectBox path as manifest-declared tools.
- JSON files placed in `vendor/jarvisos/tools/` → device path via `PRODUCT_COPY_FILES` (still needed in makefile).

### SystemToolExecutor — 16 in-process system tools
All dispatched by ToolDispatcher when `receiverClass.startsWith("@system/")`. No broadcast.

| Tool | API |
|------|-----|
| `get_battery_status` | BatteryManager |
| `get_notifications` | INotificationManager (system internal) |
| `what_is_playing` | MediaSessionManager + MediaController.getMetadata() |
| `media_control` | MediaController.getTransportControls() |
| `set_volume` | AudioManager.setStreamVolume() |
| `send_sms` | SmsManager |
| `make_phone_call` | TelecomManager.placeCall() |
| `set_alarm` | Intent.ACTION_SET_ALARM → default clock app |
| `create_contact` | ContactsContract direct insert |
| `create_calendar_event` | CalendarContract direct insert |
| `set_dnd` | NotificationManager.setInterruptionFilter() |
| `toggle_wifi` | WifiManager.setWifiEnabled() |
| `toggle_bluetooth` | BluetoothAdapter.enable/disable() |
| `set_brightness` | Settings.System.SCREEN_BRIGHTNESS |
| `set_flashlight` | CameraManager.setTorchMode() |
| `open_app` | getLaunchIntentForPackage() + startActivity() |

### Tool descriptions
Each JSON includes explicit trigger words so FunctionGemma 270M can
distinguish tools in zero-shot (e.g. "louder, quieter, turn up, turn down" for set_volume).

### Chaining scenarios documented in AGENTIC_LOOP.md
Three composite demo scenarios for dev.talk — work with current JarvisExecutor,
no code changes needed. Test on ARM once Gemma 4 is loaded.

---

## What to do next (in order)

- [ ] **PRODUCT_COPY_FILES for tool JSON** (15 min)
  Add to `vendor/jarvisos/jarvisos.mk` (or equivalent makefile):
  ```makefile
  PRODUCT_COPY_FILES += $(foreach f,$(wildcard vendor/jarvisos/tools/*.json),\
      $(f):system/etc/jarvisos/tools/$(notdir $(f)))
  ```
  Without this the JSON files won't appear at `/system/etc/jarvisos/tools/` on device.

- [ ] **Sam: pull Gemma 4 into vendor/cactus**
  `GEMMA4_MODEL_PATH` is wired. Once the GGUF is at `/data/system/jarvis/models/gemma4.gguf`
  and Cactus supports the format, `"primary"` activates automatically.
  Confirm `<|tool_call|>` token appears in `CactusWrapper.complete()` output.

- [ ] **requires_confirmation enforcement in ToolNode** (Phase 7)
  Add `requiresConfirmation` boolean to `ToolRecord`. Set from JSON loader.
  `ToolNode` pauses loop and returns a confirmation prompt before dispatching.
  Affects: `make_phone_call`, `send_sms` (and any future destructive tools).

- [ ] **lineage-22.2 re-sync** (do at uni, fast connection, ~3hrs, run in tmux)

- [ ] **On-device test on ARM hardware**
  Boot → JarvisService starts → curated tools loaded → 3 chaining scenarios from AGENTIC_LOOP.md.

---

## Key facts (carry forward)

- Tool routing is unified: all sources (JSON, manifest, system) → ObjectBox ToolRecord → single HNSW index. Source only matters at dispatch time via `receiverClass` prefix.
- `@system/` prefix → SystemToolExecutor (in-process). No broadcast, no APK needed.
- `"primary"` model entry uses `chatOnly=true` — no index, only modelHandle required for `isReady()`.
- `make_phone_call` has `requires_confirmation: true` in JSON — not enforced yet (Phase 7).
- Tool descriptions must include natural language trigger words for FunctionGemma 270M zero-shot.
- Chaining works via existing JarvisExecutor — no new infrastructure needed.
- x86_64 emulators cannot load Cactus — ARM hardware only.
