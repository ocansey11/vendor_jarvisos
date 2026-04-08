# TOOL_DISPATCHER.md — ToolDispatcher Implementation Guide
> For Desmond / anyone integrating with the Tool Registry from the app side.
> Last updated: April 2026

---

## What ToolDispatcher Does

ToolDispatcher is the component that takes a user's natural language query, finds the
right tool for it, and fires it at the correct app. It sits between JarvisService and
the installed apps.

```
JarvisService.processQuery("check my visa status")
    |
    v
ToolDispatcher.resolveAndDispatch(query)
    |
    ├── 1. Embed query → HNSW search → top-5 ToolRecords
    ├── 2. Format as tool definitions JSON
    ├── 3. CactusWrapper.complete() selects one + extracts args
    ├── 4. sendBroadcast() to app's BroadcastReceiver
    └── 5. Wait for ResultReceiver (10s timeout) → return result string
```

Returns `null` if no tool matches. JarvisService falls through to the RAG pipeline.

---

## What the App Side Must Do

If you are building an in-house app (Borderless, inventory, notes etc.) and want
Jarvis to be able to call your app's tools, you need two things:

### 1. Declare the tool in AndroidManifest.xml

```xml
<receiver
    android:name=".MyToolReceiver"
    android:exported="true"
    android:permission="android.permission.REGISTER_JARVIS_TOOL">

    <intent-filter>
        <action android:name="com.jarvisos.TOOL" />
    </intent-filter>

    <!-- Tool name — snake_case, unique within your app -->
    <meta-data
        android:name="com.jarvisos.tool.name"
        android:value="check_visa_status" />

    <!-- Plain English description — this is what gets embedded for semantic search -->
    <meta-data
        android:name="com.jarvisos.tool.description"
        android:value="Check the visa application status for a given passport number" />

    <!-- Optional: input schema (see below) -->
    <meta-data
        android:name="com.jarvisos.tool.input_schema"
        android:resource="@xml/tool_check_visa_status" />

</receiver>
```

**Important:** `android:exported="true"` is required — Jarvis sends the broadcast
from `system_server`, which is a different process. Without this the broadcast
will be silently dropped.

### 2. Write the input schema XML

`res/xml/tool_check_visa_status.xml`:

```xml
<tool-schema>
    <param
        name="passport_number"
        type="string"
        required="true"
        description="The passport number to check status for" />
    <param
        name="country_code"
        type="string"
        required="false"
        description="ISO 3166-1 alpha-2 country code, e.g. GB" />
</tool-schema>
```

### 3. Implement the BroadcastReceiver

```java
public class MyToolReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // Get the tool name (useful if one receiver handles multiple tools)
        String toolName = intent.getStringExtra("com.jarvisos.tool.name");

        // Get the ResultReceiver — used to send back the result
        ResultReceiver resultReceiver = intent.getParcelableExtra(
                "com.jarvisos.tool.result_receiver");

        if (resultReceiver == null) return; // safety check

        // Get arguments — each param comes as "com.jarvisos.tool.arg.<name>"
        String passportNumber = intent.getStringExtra("com.jarvisos.tool.arg.passport_number");
        String countryCode    = intent.getStringExtra("com.jarvisos.tool.arg.country_code");

        // Do your work
        String visaStatus = checkVisaStatus(passportNumber, countryCode);

        // Send the result back
        Bundle result = new Bundle();
        result.putString(ToolDispatcher.EXTRA_TOOL_RESULT, visaStatus);
        resultReceiver.send(ToolDispatcher.RESULT_OK, result);
    }

    private String checkVisaStatus(String passportNumber, String countryCode) {
        // Your actual logic here
        return "Visa approved. Expiry: 2027-03-01";
    }
}
```

**Key rules:**
- Always call `resultReceiver.send()` — if you don't, ToolDispatcher times out after 10 seconds
- Use `RESULT_OK` (0) for success, `RESULT_ERROR` (1) for failure
- The result string in the Bundle under `ToolDispatcher.EXTRA_TOOL_RESULT` is what
  gets returned to the user
- Keep your work fast — you have 10 seconds total. Offload slow work to a thread
  and send the result from the thread:

```java
// If your tool does async work (network, DB) — do NOT block the main thread
@Override
public void onReceive(Context context, Intent intent) {
    ResultReceiver resultReceiver = intent.getParcelableExtra("com.jarvisos.tool.result_receiver");
    String passportNumber = intent.getStringExtra("com.jarvisos.tool.arg.passport_number");

    // goAsync() keeps the receiver alive while background work runs
    PendingResult pendingResult = goAsync();

    new Thread(() -> {
        String visaStatus = checkVisaStatus(passportNumber);

        Bundle result = new Bundle();
        result.putString(ToolDispatcher.EXTRA_TOOL_RESULT, visaStatus);
        resultReceiver.send(ToolDispatcher.RESULT_OK, result);

        pendingResult.finish();
    }).start();
}
```

---

## Intent Extras Reference

### Jarvis → App (sent by ToolDispatcher)

| Extra key | Type | Description |
|---|---|---|
| `com.jarvisos.tool.name` | String | The tool name, e.g. `check_visa_status` |
| `com.jarvisos.tool.result_receiver` | ResultReceiver | Send your result back through this |
| `com.jarvisos.tool.arg.<param_name>` | String | One extra per param the model extracted |

### App → Jarvis (sent via ResultReceiver)

| Bundle key | Type | Description |
|---|---|---|
| `com.jarvisos.tool.result` | String | The result string Jarvis returns to the user |

Result codes: `ToolDispatcher.RESULT_OK` = 0, `ToolDispatcher.RESULT_ERROR` = 1

---

## How ToolDispatcher Finds Your Tool

ToolScannerService scans your app on install and:
1. Reads the manifest meta-data
2. Builds a `rawDefinition` string: `"check visa status: Check the visa application status... Parameters: passport_number, country_code"`
3. Embeds it via Cactus and stores in the tools index

At query time, ToolDispatcher embeds the user's query and does HNSW nearest-neighbour
search against all registered tool embeddings. Your tool surfaces if the semantic
similarity is high enough.

**Implication for writing descriptions:** write the description the way a user would
ask for it, not in technical terms. "Check the visa application status for a passport"
will match "what's happening with my visa" far better than "queries the visa API endpoint".

---

## Permissions

Your receiver must hold `REGISTER_JARVIS_TOOL` (signature/privileged level).
In-house apps signed by the JarvisOS key get this automatically.
Third-party apps cannot hold this permission — they cannot register tools yet.

```xml
<!-- In your app's AndroidManifest.xml -->
<uses-permission android:name="android.permission.REGISTER_JARVIS_TOOL" />
```

---

## What ToolDispatcher Does NOT Handle

- **Multiple tool calls in one query** — ToolDispatcher fires one tool per query.
  Chaining (e.g. "check visa then create a note") is Phase 5 (JarvisExecutor / agentic loop).
- **Streaming results** — the result must be a single String returned in the Bundle.
  Long-running tools should return a summary, not a stream.
- **Tool auth / user confirmation** — no per-tool permission prompt yet. Phase 5 item.
- **Curated (first-party) tools** — tools defined in `vendor/jarvisos/tools/*.json`
  are not yet loaded. ToolScannerService only processes manifest-declared tools for now.

---

## Files

| File | Package | Role |
|---|---|---|
| `ToolDispatcher.java` | `com.android.server.rag.tools` | Resolve + dispatch |
| `ToolScannerService.java` | `com.android.server.rag.tools` | Scan + embed on install |
| `AppRecord.java` | `com.android.server.rag.tools` | ObjectBox: one per app |
| `ToolRecord.java` | `com.android.server.rag.tools` | ObjectBox: one per tool |

---

## Known Gaps

| Gap | Notes |
|---|---|
| `ToolRecord_` / `AppRecord_` not yet in Android.bp | ObjectBox query classes need wiring into the build. Will cause compile error until fixed. |
| No `IToolRegistry.aidl` yet | Apps cannot query the registry directly. Coming next. |
| Curated tools not loaded | `vendor/jarvisos/tools/*.json` files not yet read by ToolScannerService. |
| No retry on embed failure | If Cactus handles aren't ready at install time, tool is stored without embedding. Re-embedding on next boot is not yet implemented. |
| Single tool call only | Multi-tool orchestration is Phase 5 (JarvisExecutor). |
