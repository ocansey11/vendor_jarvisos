# TOOL_REGISTRY.md — JarvisOS Tool Registry Spec
> Design spec for cross-app tool discovery and orchestration.
> Last updated: March 2026

---

## Problem

JarvisOS needs to let apps expose agentic tools (actions) so Jarvis can orchestrate across them.
Example: Borderless (immigration) exposes `check_visa_status`. Notes app exposes `create_note`.
Jarvis should be able to call either based on user intent — without hardcoding anything.

---

## Solution: Manifest-First, Runtime-Optional

Tools are declared in the app's `AndroidManifest.xml` at build time.
JarvisOS scans on APK install — no app needs to be running.
Apps that need dynamic tools can additionally register at runtime to extend or override.

This mirrors Android's own pattern: static permissions + dynamic permissions.

---

## Manifest Declaration

Developers declare a `<receiver>` with the JarvisOS tool intent action and hang metadata off it:

```xml
<receiver android:name=".BorderlessToolReceiver">
    <intent-filter>
        <action android:name="com.jarvisos.TOOL" />
    </intent-filter>
    <meta-data android:name="com.jarvisos.tool.name"
               android:value="check_visa_status" />
    <meta-data android:name="com.jarvisos.tool.description"
               android:value="Check the visa application status for a given passport number" />
    <meta-data android:name="com.jarvisos.tool.input_schema"
               android:resource="@xml/tool_check_visa_status" />
</receiver>
```

### Namespace convention

`com.jarvisos.tool.*` — reverse domain, consistent with Android conventions.

If this were stock Android it would be `android.tool.*`. For JarvisOS it is `com.jarvisos.tool.*`.
This is not registered anywhere — it is a string convention that JarvisOS defines and documents.
Any app that uses this string in their manifest is opting in.

### Input schema

Complex parameter definitions live in a separate XML resource (e.g. `res/xml/tool_check_visa_status.xml`)
so the manifest stays clean:

```xml
<!-- res/xml/tool_check_visa_status.xml -->
<tool-schema>
    <param name="passport_number" type="string" required="true"
           description="The passport number to check status for" />
    <param name="country_code"    type="string" required="false"
           description="ISO 3166-1 alpha-2 country code" />
</tool-schema>
```

---

## ToolScannerService

Listens for `ACTION_PACKAGE_ADDED` broadcast. On APK install:

```java
Intent queryIntent = new Intent("com.jarvisos.TOOL");
List<ResolveInfo> tools = packageManager.queryBroadcastReceivers(
    queryIntent, PackageManager.GET_META_DATA
);
```

PackageManager already indexes every declared receiver — no JarvisOS registry needed.
The namespace `com.jarvisos.TOOL` is the opt-in contract.

For each result:
1. Read `com.jarvisos.tool.name`, `description`, `input_schema` from meta-data
2. Parse input schema XML
3. Store `ToolDefinition` in ObjectBox
4. Embed tool description via Cactus → store vector in Cactus index

On `ACTION_PACKAGE_REMOVED`: delete corresponding ObjectBox entries and Cactus index entries.

---

## ObjectBox Schema

```java
@Entity
public class ToolDefinition {
    @Id long id;
    String packageName;          // e.g. com.borderless.app
    String receiverClass;        // e.g. .BorderlessToolReceiver
    String toolName;             // e.g. check_visa_status
    String description;          // natural language, used for embedding
    String inputSchemaJson;      // parsed + serialised from XML resource
    int cactusIndexId;           // pointer to Cactus embedding index
    long installedAt;
    long lastUpdatedAt;
}
```

---

## Query Flow (Tool Routing)

```
User: "check my visa for my trip"
    |
    Stage 1 — Metadata search (instant)
      Search ToolDefinition.toolName + description keywords
      Returns candidate tools
    |
    Stage 2 — Semantic search (if Stage 1 insufficient)
      cactus_embed(query)
      cactus_index_query() over tool description embeddings
      Returns best matching ToolDefinition
    |
    Stage 3 — Invocation
      Send broadcast to packageName / receiverClass
      Pass resolved parameters as Bundle extras
      App executes tool, returns result via callback AIDL
    |
    Stage 4 — Response
      Jarvis receives result, passes to LLM for natural language response
```

Same two-stage retrieval pattern as MEMORY_SCHEMA.md — consistent across memory and tools.

---

## Cross-App Orchestration Example

```
User: "add a note about my visa status"

Jarvis resolves two tools:
  1. check_visa_status  → Borderless
  2. create_note        → Notes app

Executes sequentially:
  → calls Borderless: check_visa_status(passport_number="AB123456")
  → receives result: { status: "approved", expiry: "2027-03-01" }
  → calls Notes: create_note(content="Visa approved, expires 2027-03-01")
  → responds to user: "Done — I checked your visa and saved the result as a note."
```

---

## Future: Runtime Registration (Phase extension)

For dynamic tools (e.g. inventory app tools change based on stock):

```java
// App calls at runtime to register additional or override tools
IJarvisService jarvis = IJarvisService.Stub.asInterface(...);
jarvis.registerTool(new DynamicToolDefinition(...));
```

Runtime registrations are session-scoped — cleared when app process dies.
Manifest declarations are permanent — persist across reboots.

---

## Permissions Model

### Permission declarations (defined in JarvisOS framework)

```xml
<!-- Any app can request this — user sees a runtime popup on install -->
<permission
    android:name="android.permission.ACCESS_RAG_SERVICE"
    android:protectionLevel="dangerous" />

<!-- Only apps signed by JarvisOS cert or shipped with ROM can hold this -->
<permission
    android:name="android.permission.REGISTER_JARVIS_TOOL"
    android:protectionLevel="signature|privileged" />
```

### What each permission gates

| Permission | Who gets it | What it allows |
|---|---|---|
| `ACCESS_RAG_SERVICE` | Any app, user approves at install | Call `processQuery()`, `indexDocument()`, `isIndexed()` |
| `REGISTER_JARVIS_TOOL` | JarvisOS-signed apps only | Expose tools via Tool Registry, register via `REGISTER_JARVIS_TOOL` broadcast |

### Developer tracks

**Track 1 — In-house apps** (Borderless, Notes, Inventory, etc.)
Built and signed by the JarvisOS team. Get `REGISTER_JARVIS_TOOL` automatically.
Full agentic capability — can both query Jarvis and expose tools back to it.

**Track 2 — Third-party developers**
Can query Jarvis via `ACCESS_RAG_SERVICE` (user approves).
Cannot register tools until they go through the JarvisOS signing/review process.
This is the future Developer Portal work — SDK spec, documentation, cert issuance.

### Runtime popup (Track 2 apps)
When a third-party app requests `ACCESS_RAG_SERVICE`, the user sees:
*"[App name] wants to access your on-device AI assistant."*
User approves once. Permission is retained until manually revoked in Settings.

---

## Next Steps

- [ ] Implement `ToolDefinition` ObjectBox entity in `frameworks/base/services/core/java/com/android/server/rag/`
- [ ] Implement `ToolScannerService` — listens for `ACTION_PACKAGE_ADDED`
- [ ] Wire PackageManager query for `com.jarvisos.TOOL` receivers
- [ ] Parse input schema XML from APK resources
- [ ] Embed tool descriptions via CactusWrapper, store in Cactus index
- [ ] Implement tool invocation via broadcast + callback AIDL
- [ ] Test end-to-end with a stub Borderless app
- [ ] Document developer-facing SDK spec (separate doc)
