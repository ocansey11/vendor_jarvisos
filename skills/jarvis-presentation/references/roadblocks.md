# JarvisOS roadblocks, in order

Times are local to the build server (CEST). "Proven to" is the furthest level
at which the fix has actually been seen working:
**compiles** → **ROM builds** → **server tests** → **emulator** → **phone**.

Entries 1–6 are reconstructed from commit messages, build logs and session
notes; entries 7 onward were written as they happened.

## Contents

1. Twelve sessions of code that had never been compiled
2. The service was in a folder that could not see its own database library
3. WorkManager inside system_server
4. The inference engine fork was a year behind
5. A prebuilt library that did not say what it needed
6. The backup that was not a copy
7. A database layer made of placeholders
8. The code generator that phones home
9. A missing library could have stopped the phone booting
10. A test that had never run found a real bug
11. Cuttlefish would not install
12. The emulator wants 16K pages
13. First boot: Android started, Jarvis did not
14. The file watcher that watches nothing (open)
15. Every app that answered a tool call crashed
16. Any app could have triggered any other app's tools
17. A prediction that was wrong
18. The test was wrong, and the service hid it

---

## 1. Twelve sessions of code that had never been compiled

- **When:** before 2 Oct 2026 (background to everything else).
- **Expected:** about 6,000 lines of service code, written over 12 sessions,
  were close to done.
- **What happened:** they were written on a laptop that could not build
  Android. Nothing had ever been compiled, linked or run.
- **How found:** renting a build server (12 threads, 64 GB) and building.
- **For the talk:** the starting point. Every later entry is a consequence of
  "written" not meaning "built".

## 2. The service was in a folder that could not see its own database library

- **When:** 2 Oct 2026, about 10:17, first compile attempt.
- **Expected:** putting the code under `services/core/java/...` like other
  system services would just work.
- **What happened:** `services.core` compiles everything under that folder and
  has no ObjectBox or androidx on its classpath:

  ```
  frameworks/base/services/core/java/com/android/server/jarvis/agent/DreamWorker.java:10:
      error: symbol not found androidx.work.Worker
  ```
- **Fix:** move the service to its own module, `services/jarvis/`, with its
  own `Android.bp` and dependencies, linked into `services.jar`.
- **Proven to:** emulator.
- **Commit:** `android_frameworks_base` `879961b84755`.
- **For the talk:** in Android the folder a file sits in decides what it can
  import.

## 3. WorkManager inside system_server

- **When:** 2 Oct 2026, 11:14. First full ROM build, failed after 23 minutes.
- **Expected:** `DreamWorker` (nightly memory consolidation) and the file
  indexer could use androidx WorkManager, as an app would.
- **What happened:** the code compiled, then the whole `services.jar` failed
  at the shrinking step:

  ```
  Error: Missing class androidx.core.R$attr (referenced from: ...
      androidx.core.content.res.ColorStateListInflaterCompat.inflate(...))
  ```
- **Cause:** WorkManager is an app library. It drags in androidx resources
  that do not exist inside `system_server`, and it would not have initialised
  there anyway.
- **Before** (`agent/DreamWorker.java`):

  ```java
  public class DreamWorker extends Worker {
      public DreamWorker(Context context, WorkerParameters params) {
          super(context, params);
      ...
      PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
              DreamWorker.class, 24, TimeUnit.HOURS)
              .setConstraints(constraints)
              .build();
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(
              WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work);
  ```
- **After:**

  ```java
  public class DreamWorker {
      public DreamWorker(Context context) {
          mContext = context;
      ...
      DreamWorker worker = new DreamWorker(context);
      JarvisScheduler.schedulePeriodic(context, WORK_NAME,
              1, 24, TimeUnit.HOURS, /* requiresCharging= */ true, worker::doWork);
  ```
  `JarvisScheduler` is 80 lines: one low-priority thread and an in-memory
  schedule that is re-registered at boot.
- **Proven to:** emulator (both jobs log "Scheduled"); they have not yet fired.
- **Commit:** `android_frameworks_base` `24e18194cb1a`.
- **For the talk:** system services are not apps. The convenient library is
  often unavailable one layer down.

## 4. The inference engine fork was a year behind

- **When:** 2 Oct 2026, morning.
- **Expected:** the `cactus` fork would build as it was.
- **What happened:** the fork was based on v1.6; upstream was v2.2.2 and had
  moved its sources into three components and changed the completion call.
- **Fix:** re-port onto v2.2.2 as three added files instead of patches to
  upstream files: a Soong build file, a standalone JNI file, and a no-op
  telemetry stub. With the stub and without libcurl the library contains no
  network code.
- **Proven to:** ROM builds (arm64). Never run: no phone, and it cannot be
  built for the x86 emulator.
- **Commit:** `cactus` `832af6b`, `e539800`.
- **For the talk:** keeping additions in separate files is what made a
  one-year jump a morning's work.

## 5. A prebuilt library that did not say what it needed

- **When:** 2 Oct 2026, 11:37. Second full build, stopped at about 80%.
- **What happened:**

  ```
  libobjectbox-jni.so: error: DT_NEEDED "liblog.so" is not specified in shared_libs.
  ```
- **Before** (`vendor/jarvisos/prebuilts/objectbox/Android.bp`):

  ```
  cc_prebuilt_library_shared {
      name: "libobjectbox-jni",
      ...
      strip: { none: true },
  }
  ```
- **After:** one added line, `shared_libs: ["liblog"],`.
- **Proven to:** emulator.
- **Commit:** `vendor_jarvisos` `1bcbb06`.
- **For the talk:** the third full build, at 12:06, was the first to succeed.
  Three builds, three different layers of failure.

## 6. The backup that was not a copy

- **When:** 2 Oct 2026, 12:06, discovered right after the first good build.
- **Expected:** the vanilla LineageOS ROM from 20 Sep was safely backed up in
  `~/artifacts/`.
- **What happened:** the "backup" was a hard link to the file in the build
  output folder. The Jarvis build rewrote that file in place, so the backup
  now contained the Jarvis ROM under the vanilla name. No other copy existed.
- **Fix:** back up ROMs with `cp`, never `ln`.
- **For the talk:** the only data lost in the whole project, and it had
  nothing to do with Android.

## 7. A database layer made of placeholders

- **When:** 2 Oct 2026, about 12:40.
- **Expected:** the ROM built, so the database code was at least plausible.
- **What happened:** ObjectBox normally generates its record-access classes
  at build time. That generator had never been wired in, so the classes were
  hand-written placeholders. Any read or write would have thrown on a device.
- **How found:** reading the code while planning what to test.
- **Before** (`tools/ToolRecord_.java`, and the same in nine other classes):

  ```java
  @Override
  public CursorFactory<ToolRecord> getCursorFactory() {
      throw new UnsupportedOperationException(
              "ToolRecord cursor factory not generated. "
              + "Run objectbox-processor to produce ToolRecordCursor.");
  }
  ```
  and in `core/MyObjectBox.java`:

  ```java
  BoxStoreBuilder builder = BoxStoreBuilder.createDebugWithoutModel();
  ```
- **After:** 25 files generated by ObjectBox's real processor, run on the
  build server by `services/jarvis/objectbox/regenerate.sh` and checked in.
  Entities with relations had to do by hand what ObjectBox's Gradle plugin
  normally does to the bytecode:

  ```java
  transient BoxStore __boxStore;
  ...
  public ToOne<AppRecord> app = new ToOne<>(this, ToolRecord_.app);
  ```
- **Proven to:** emulator (store opens inside `system_server`, 14 tools
  written and read back). 36 server checks pass.
- **Commit:** `android_frameworks_base` `2b1204c55300`.
- **For the talk:** a successful build said nothing about whether the
  database could store a single record.

## 8. The code generator that phones home

- **When:** 2 Oct 2026, about 12:47.
- **What happened:** ObjectBox's processor crashed with
  `NoClassDefFoundError: com/google/crypto/tink/aead/AeadConfig` inside
  `io.objectbox.reporting.BasicBuildTracker.sendEvent`. It reports build
  statistics to ObjectBox on every run.
- **Fix:** the regeneration script runs it in an empty network namespace:

  ```bash
  unshare -n "$JAVAC" -proc:only ... -processor io.objectbox.processor.ObjectBoxProcessorShim
  ```
- **Note:** the very first trial run was not sandboxed and may have sent one
  report.
- **For the talk:** a privacy-first OS, and the build tooling for its database
  has telemetry. Found only because a dependency happened to be missing.

## 9. A missing library could have stopped the phone booting

- **When:** 2 Oct 2026, about 12:50.
- **How found:** reading the start-up code before the first emulator boot.
- **Cause:** a missing native library raises an `Error`, not an `Exception`.
  An uncaught error on a thread inside `system_server` kills it.
- **Before** (`JarvisService.java`):

  ```java
  } catch (Exception e) {
      Log.e(TAG, "JarvisService init failed", e);
  }
  ```
- **After:**

  ```java
  } catch (Throwable t) {
      // Throwable, not Exception: a missing native library surfaces as
      // an Error, and an uncaught one on this thread kills system_server.
      Log.e(TAG, "JarvisService init failed", t);
  }
  ```
- **Proven to:** emulator. `libcactus.so` is absent there; the log shows the
  `UnsatisfiedLinkError`, three "model not ready" lines, and the service
  finishing start-up.
- **Commit:** `android_frameworks_base` `8ac112d7c4e5`.
- **For the talk:** one word, `Exception` to `Throwable`, between "feature
  unavailable" and "phone does not boot".

## 10. A test that had never run found a real bug

- **When:** 2 Oct 2026, about 12:53.
- **What happened:** the unit test project pointed at the pre-port folder and
  did not build. Once repaired, 28 of 29 tests passed. The failure:
  `DreamWorkerTest > Final answer appears in summary when present`.
- **Before** (`agent/DreamWorker.java`):

  ```java
  if (session.turns == null || session.turns.isEmpty()) {
      return session.originalQuery != null
              ? "User asked: " + session.originalQuery
              : null;
  }
  ```
- **After:**

  ```java
  if (session.turns == null || session.turns.isEmpty()) {
      if (session.originalQuery == null) return null;
      String summary = "User asked: " + session.originalQuery;
      return session.finalAnswer != null
              ? summary + "\nFinal answer: " + truncate(session.finalAnswer, 200)
              : summary;
  }
  ```
- **Proven to:** server tests (29 of 29).
- **Commit:** `android_frameworks_base` `51dfc3cca371`.
- **For the talk:** the test was written months earlier and was right. It had
  just never been run.

## 11. Cuttlefish would not install

- **When:** 2 Oct 2026, about 12:55.
- **Expected:** boot the ROM in Cuttlefish, Google's virtual Android device.
- **What happened:** `cuttlefish-base : Depends: libc6 (>= 2.39)`. Every
  published version needs a newer glibc than Ubuntu 22.04 has.
- **Fix:** use the standard Android emulator, which is already in the source
  tree, with the `lineage_sdk_phone_x86_64` target. Three small Ubuntu
  libraries were all it needed.
- **For the talk:** the plan changed in ten minutes because the fallback was
  already on disk.

## 12. The emulator wants 16K pages

- **When:** 2 Oct 2026, 12:58.
- **What happened:**

  ```
  libobjectbox-jni.so: error: Load segment has alignment 4096 but 16384 required.
  ```
- **Cause:** the emulator product checks every native library for 16K page
  alignment. ObjectBox's x86_64 prebuilt is 4K-aligned.
- **Fix:** `ignore_max_page_size: true` for that one variant.
- **Proven to:** emulator.
- **Commit:** `vendor_jarvisos` `1bcbb06`.
- **For the talk:** a preview of a real future problem. Phones are moving to
  16K pages and closed-source prebuilts have to follow.

## 13. First boot: Android started, Jarvis did not

- **When:** 2 Oct 2026, 15:17. First emulator boot, after a two-hour build.
- **Expected:** the service would start and report its models as missing.
- **What happened:** Android booted normally. The Jarvis service did not
  exist:

  ```
  E SystemServer: BOOT FAILURE starting Jarvis Service
  E SystemServer: java.lang.ExceptionInInitializerError
  Caused by: java.lang.NullPointerException: Attempt to invoke interface method
      '... IStorageManager.getVolumeList(...)' on a null object reference
      at android.os.Environment.getExternalStorageDirectory(Environment.java:794)
      at com.android.server.jarvis.JarvisService.<clinit>(JarvisService.java:66)
  ```
- **Cause:** a static field asked Android for the shared-storage path while
  the class was loading. Inside `system_server` the storage service does not
  exist yet at that point.
- **Before** (`JarvisService.java`):

  ```java
  private static final String[] WATCH_PATHS = {
      Environment.getExternalStorageDirectory().getAbsolutePath() + "/Documents",
      Environment.getExternalStorageDirectory().getAbsolutePath() + "/Downloads",
      Environment.getExternalStorageDirectory().getAbsolutePath() + "/Pictures",
  };
  ```
- **After:**

  ```java
  private static final String SHARED_STORAGE = "/storage/emulated/0";
  private static final String[] WATCH_PATHS = {
      SHARED_STORAGE + "/Documents",
      SHARED_STORAGE + "/Downloads",
      SHARED_STORAGE + "/Pictures",
  };
  ```
- **Proven to:** emulator (second boot, 15:21: `JarvisService initialized`;
  still up, with the same 14 tools, after a reboot).
- **Commit:** `android_frameworks_base` `898250640967`.
- **For the talk:** the strongest slide. 65 passing tests, a ROM that builds
  and boots, and the feature the ROM exists for was not running. On the phone
  it would have looked like a working install with a silent Jarvis.

## 14. The file watcher that watches nothing (open)

- **When:** 2 Oct 2026, 15:21, second emulator boot.
- **What happened:** the log reports success and does nothing:

  ```
  I JarvisFileObserver: Watching (recursive): /storage/emulated/0/Documents — 0 director(ies)
  ```
- **Cause:** `system_server` cannot see the user's shared storage. Automatic
  indexing of documents cannot work from inside the service as designed.
- **Status:** not fixed. Needs a design change, probably a small companion
  app that watches files and hands them to the service.
- **For the talk:** a log line that says "Watching" is not evidence of
  watching. Also the clearest example of an architecture decision that only a
  running system could disprove.

## 15. Every app that answered a tool call crashed

- **When:** 2 Oct 2026, 15:24. First end-to-end test with a sample app.
- **Expected:** Jarvis calls the app's `set_alarm` tool and gets "Alarm set".
- **What happened:** Jarvis found the app's three tools, but 12 of 19 checks
  failed with `Error: tool timed out after 10000ms`. The app had crashed:

  ```
  E Parcel: Class not found when unmarshalling:
      com.android.server.jarvis.tools.ToolDispatcher$1
  E AndroidRuntime: java.lang.RuntimeException: Unable to start receiver
      com.jarvisos.demo.alarms.ListAlarmsTool: android.os.BadParcelableException
  ```
- **Cause:** Jarvis hands the app a reply channel. It was an anonymous
  subclass, which Android packs under its own class name. That class exists
  only inside `system_server`, so no app could unpack it.
- **Before** (`tools/ToolDispatcher.java`):

  ```java
  intent.putExtra("com.jarvisos.tool.result_receiver", receiver);
  ...
  mContext.sendBroadcast(intent);
  ```
- **After:**

  ```java
  intent.putExtra("com.jarvisos.tool.result_receiver", toPlainReceiver(receiver));
  ...
  mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
  ```
  ```java
  private static ResultReceiver toPlainReceiver(ResultReceiver receiver) {
      Parcel parcel = Parcel.obtain();
      try {
          receiver.writeToParcel(parcel, 0);
          parcel.setDataPosition(0);
          return ResultReceiver.CREATOR.createFromParcel(parcel);
      } finally {
          parcel.recycle();
      }
  }
  ```
- **Proven to:** emulator. 15:52, all 22 checks of
  `tests/emulator/app-tools.sh` pass: set 07:00 → "Alarm set for 07:00.",
  set it again → "An alarm for 07:00 already exists. Nothing was changed."
- **Commit:** `android_frameworks_base` `93d244ffd63d`.
- **For the talk:** the whole "apps give Jarvis tools" idea rested on a call
  path that had never once been exercised. The first real app to answer
  crashed.

## 16. Any app could have triggered any other app's tools

- **When:** 2 Oct 2026, 15:24, same test.
- **How found:** a log line during the tool call:

  ```
  E ActivityManager: Sending non-protected broadcast com.jarvisos.TOOL from system
  ```
- **Cause:** tool receivers must be exported so Jarvis can reach them, and
  nothing restricted who else could send the same broadcast.
- **Fix** (`core/res/AndroidManifest.xml`):

  ```xml
  <protected-broadcast android:name="com.jarvisos.TOOL" />
  ```
- **Proven to:** emulator. Sending the same broadcast from the adb shell now
  gives `SecurityException: Permission Denial: not allowed to send broadcast
  com.jarvisos.TOOL from pid=2759, uid=2000`, and no alarm is created.
- **Commit:** `android_frameworks_base` `93d244ffd63d`.
- **For the talk:** a security hole found from a warning in a log, while
  chasing a different bug.

## 17. A prediction that was wrong

- **When:** 2 Oct 2026, 15:24.
- **Predicted:** calls to a freshly installed, never-opened app would be
  blocked, because Android does not deliver broadcasts to "stopped" apps.
- **What happened:** the broadcast was delivered and the app's process
  started. The failure was entry 15 instead.
- **For the talk:** two of three predictions about the emulator were right
  (entries 13 and 14). The wrong one is worth showing: reasoning about a
  system is not the same as running it.

## 18. The test was wrong, and the service hid it

- **When:** 2 Oct 2026, 15:48. Third emulator boot, after a 22-minute rebuild.
- **Expected:** with entry 15 fixed, all sample-app checks would pass.
- **What happened:** 11 of 19 still failed, now with
  `Error: tool returned error code 1`. The tool with no arguments worked;
  every tool with arguments failed.
- **Cause:** two things stacked.
  1. The test script's own bug: the device's shell stripped the quotes from
     the JSON, so `{"hour":7,"minute":0}` arrived as `hour:7`.
  2. The service made that invisible. It logged the bad JSON, called the tool
     anyway with no arguments, and then replaced the app's explanation
     ("missing argument 'hour'") with a bare error code.
- **Before** (`tools/ToolDispatcher.java`):

  ```java
  } catch (JSONException e) {
      Log.w(TAG, "dispatchByName: invalid argsJson — " + argsJson);
  }
  ...
  } else {
      resultRef.set("Error: tool returned error code " + resultCode);
  }
  ```
- **After:**

  ```java
  } catch (JSONException e) {
      Log.w(TAG, "dispatchByName: invalid argsJson — " + argsJson);
      return "Error: arguments are not valid JSON: " + argsJson;
  }
  ...
  } else {
      // Pass the app's own explanation on: the model can only
      // correct a bad call if it is told what was wrong with it.
      String detail = resultData != null
              ? resultData.getString(EXTRA_TOOL_RESULT) : null;
      resultRef.set(detail != null && !detail.isEmpty()
              ? detail : "Error: tool returned error code " + resultCode);
  }
  ```
- **Proven to:** emulator (22 of 22, including "hour 25 is rejected" with the
  app's own message and "broken JSON is rejected, not run").
- **Commit:** `android_frameworks_base` `93d244ffd63d`.
- **For the talk:** a failing test is not always a bug in the product, but the
  wrong test still found something. An error message that reaches the model
  matters more for an agent than for a person: it is the only way the model
  can fix its own call.

