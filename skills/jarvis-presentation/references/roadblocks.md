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
19. Getting a 1.65 GB ROM off the server
20. The PIN screen went black
21. The demo app had vanished
22. Anyone with a shell could ask Jarvis anything
23. A "25 minute" build that took 44
24. The keyboard covered the conversation, and the Run button
25. Building llama.cpp without its own build system
26. Three bugs that would have stopped any model calling a tool
27. No way to put a model on the device
28. "How much battery do I have?" got a lecture instead of an answer
29. The flashlight was never a tool
30. An engine with no build for the emulator
31. Confidence that moved when the list was reordered
32. The robot voice Jarvis could not understand
33. The phone build was harder than the emulator hack
34. "Right tool" was not "it worked": the shell ran tools as the shell
35. "How are you doing today" read the notifications
36. Apps in the ROM had no tools after boot
37. "Required" did not mean "no talking"
38. The tool did the maths; the model misread it (open)
39. Half an emoji stopped the test
40. Smaller ones from the tic-tac-toe day
41. The rules lived in notes, not in the tools (the harness)
42. Cactus could not run on the test phone at all
43. `__fp16` is not a real number on x86
44. Translated code that broke on two ordinary C++ habits
45. The server test could not see an Android-only header
46. A warning became a link error
47. There was no way to install a Cactus model (found for the phone)
48. "auto" would pick an engine with no model (open, phone)
49. Smaller ones from the Cactus day
50. Cactus had never been given a tool

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
19. Getting a 1.65 GB ROM off the server
20. The PIN screen went black
21. The demo app had vanished
22. Anyone with a shell could ask Jarvis anything
23. A "25 minute" build that took 44
24. The keyboard covered the conversation, and the Run button
25. Building llama.cpp without its own build system
26. Three bugs that would have stopped any model calling a tool
27. No way to put a model on the device
28. "How much battery do I have?" got a lecture instead of an answer

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


## 19. Getting a 1.65 GB ROM off the server

- **When:** 2 Oct 2026, 16:35 to 23:35.
- **Context:** the phone ROM was rebuilt at 16:35 with the day's emulator
  fixes (`lineage-22.2-20261002-JARVIS-Pong-r2.zip`). Kevin wanted it in his
  Google Drive folder.
- **What happened:**
  1. The Drive connector in Claude only takes small text files. The checksum
     file went up that way; the ROM could not.
  2. rclone was installed. Google sign-in needs a browser, and the server has
     none. The sign-in link points at the server's own `127.0.0.1`, so it was
     resolved on the server and the Google link handed to Kevin instead.
     Kevin also ran the command himself and got `bind: address already in
     use`, because the agent's attempt was still holding the port.
  3. Sign-in worked and a small test file uploaded. The ROM uploaded to 100%
     and was then rejected at the final step, every time, with
     `Error 403: Quota exceeded ... rateLimitExceeded`. The limit is on
     rclone's shared Google app, not on Kevin's account (86 GB free).
- **Outcome:** not solved from the server. Kevin's suggestion was the simple
  one: pull the file to his own PC with `scp`. Direct upload would need his
  own Google API credentials for rclone.
- **Proven to:** sign-in and small writes work; large uploads through the
  shared rclone app do not. Kevin then pulled the zip with `scp`, uploaded it
  to Drive himself and shared it with his group (confirmed 2 Oct 2026).
- **For the talk:** seven hours after the code worked, the remaining problem
  was moving a file. The user's one-line idea beat the agent's tooling.

## 20. The PIN screen went black

- **When:** 5 Oct 2026, about 00:45. Kevin viewing the emulator from his
  Windows PC with scrcpy over an SSH tunnel, for the first time.
- **Expected:** click through Android's first-time setup and reach the home
  screen.
- **What happened:** at "choose a screen lock" the screen turned black and
  stayed black.
- **How it was found:** on the server, `dumpsys window` showed the PIN screen
  was open and waiting with the keyboard up; its window flags included
  `SECURE`.
- **Cause:** not a bug. Android marks password and PIN screens `FLAG_SECURE`,
  which makes screen capture (scrcpy, screenshots) show black, so other apps
  cannot record a password being typed.
- **Fix:** Claude drove the setup from the server with `adb` (`uiautomator
  dump` to find buttons, `input tap` to press them): Skip on the PIN screen,
  Skip on the warning, then through to the home screen. No code change.
- **Proven to:** emulator.
- **For the talk:** a security feature looked exactly like a crash. Checking
  what the system thinks is on screen beat guessing from what the viewer
  shows.

## 21. The demo app had vanished

- **When:** 5 Oct 2026, about 00:53, when Kevin asked to watch the alarm demo.
- **What happened:** every call failed with `Error: tool 'demo_list_alarms'
  not registered`.
- **Cause:** the emulator had started with a fresh, empty phone (the same
  reason the setup screens appeared), and the demo app had only ever been
  installed by hand with `adb install`, not built into the emulator image.
- **Fix:** reinstalled the APK; Jarvis registered the three tools on its own
  within seconds, and the demo then ran: list, set 07:30, set again ("already
  exists. Nothing was changed."), list, and a missing-argument error. Run
  twice, the second time for Desmond.
- **Proven to:** emulator.
- **For the talk:** the reinstall doubled as a test nobody planned: tool
  discovery on install worked on a clean phone.

## 22. Anyone with a shell could ask Jarvis anything

- **When:** 5 Oct 2026, 18:30, while building the Jarvis app. Known since the
  port (it was a `TODO` in the code), closed now because the app needed it.
- **What happened:** the two Binder services checked nothing about the caller.
  From an ordinary `adb shell`, before the fix:

  ```
  $ service call jarvis_tools 1          # listTools()
  Result: Parcel( 00000000 00003764 '....d7..[.{.".i.d.".:.1.,.".t.o.o.l.N.a.m.e...'
  $ service call jarvis 1 s16 hello      # processQuery("hello")
  Result: Parcel( 00000000 00000037 '....7...I. .w.a.s.n.'.t. .a.b.l.e...'
  ```
- **Before** (`JarvisService.java`):

  ```java
  private void enforceCallingPermission() {
      final int callingUid = Binder.getCallingUid();
      Log.d(TAG, "Called by UID: " + callingUid);
      // TODO: mContext.enforceCallingPermission("android.permission.ACCESS_JARVIS_SERVICE", "...");
  }
  ```
- **After:** a signature-level permission declared in the framework manifest,
  held only by apps signed with the platform key (the Jarvis app), and
  enforced on every call:

  ```xml
  <permission android:name="android.permission.ACCESS_JARVIS_SERVICE"
      android:protectionLevel="signature" />
  ```
  ```java
  private void enforceCallingPermission() {
      mContext.enforceCallingOrSelfPermission(PERMISSION, "jarvis");
  }
  ```
  The same two commands now return an exception (`ffffffff ...`) instead of
  data. `cmd jarvis ...` still works for the shell: it has its own shell/root
  check and no longer goes through the Binder method.
- **Proven to:** emulator (`tests/emulator/jarvis-app.sh`, "Callers without
  the permission").
- **For the talk:** SELinux already kept third-party apps out, so this was a
  second lock, not the only one. The shell got in because SELinux lets it
  look up almost any service. One layer of defence looked like enough until
  somebody tried the door.

## 23. A "25 minute" build that took 44

- **When:** 5 Oct 2026, 18:31 to 19:15.
- **Predicted:** Claude told Kevin about 25 minutes, from the note that a
  change to `core/res/AndroidManifest.xml` cost about that on 2 Oct.
- **What happened:** 15 minutes in, the build was at 27% of 13,110 steps and
  estimating 1h37m more. Kevin asked "how many minutes more"; the revised
  answer was "an hour, possibly 1h30". It finished 29 minutes after that.
- **Cause:** adding a permission changes the framework's resource package,
  and every app in the image is re-linked against it. Both estimates were
  wrong, in opposite directions.
- **For the talk:** one line of XML, 13,000 build steps. And ninja's
  early "time remaining" is no better than a person's guess.

## 24. The keyboard covered the conversation, and the Run button
25. Building llama.cpp without its own build system
26. Three bugs that would have stopped any model calling a tool
27. No way to put a model on the device
28. "How much battery do I have?" got a lecture instead of an answer

- **When:** 5 Oct 2026, 19:17 and 19:21, first runs of the Jarvis app on the
  emulator.
- **Expected:** type "hello" in Chat, see "hello" and the reply.
- **What happened:** the automated check "the question appears in the
  conversation" failed. A screenshot showed why: with the keyboard up, the
  "no model loaded" notice and the text box used all the height, and the
  conversation was a sliver a few pixels tall. In the tool form the same
  keyboard covered the Run and Close buttons.
- **How it was found:** the first by the test script, then a screenshot; the
  second only by looking at a screenshot, after a tap meant for Run landed on
  the keyboard and typed "gymnasium" into the alarm label.
- **Fix:** the notice became the first row of the conversation, so it scrolls
  away (`item_chat_notice.xml`, added with `ListView.addHeaderView`); the
  tool form's window now resizes for the keyboard:

  ```java
  dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  ```
- **Proven to:** emulator (16 of 16 app checks; alarm set through the form,
  then "already exists. Nothing was changed." on the second press).
- **For the talk:** the app compiled, installed and passed 15 of 16 checks
  while being unusable for its main purpose on a small screen.
  Screenshots of the fixed app are in `assets/2026-10-05-jarvis-app-*.png`
  (none was kept of the broken layout).

## 25. Building llama.cpp without its own build system

- **When:** 5 Oct 2026, 21:06 to 21:25. Kevin had forked llama.cpp as a
  second engine beside Cactus (`ocansey11/llama.cpp`).
- **Expected:** llama.cpp builds with CMake; Android's platform build (Soong)
  does not run CMake, so the source list had to be written by hand in an
  `Android.bp`, as was done for Cactus.
- **What happened:** five attempts, four failures, each a different rule of
  the Android build:
  1. `multiple rules generate .../ggml/src/ggml.o`: `ggml.c` and `ggml.cpp`
     compile to the same object name. Fix: the C files became their own
     static library.
  2. `error: use of dynamic_cast requires -frtti`, although `-frtti` was in
     the flags: Soong adds `-fno-rtti` after it. Fix: the property
     `rtti: true`.
  3. `use of undeclared identifier 'common_parse_utf8_codepoint'`: `src/` and
     `common/` each have a `unicode.h` and the wrong one was found. Fix: two
     libraries with separate include paths, as upstream has.
  4. `undefined symbol: dl_load_library`: one source file missing from the
     list.
- **Proven to:** emulator. `libllama_jarvis.so` (5.8 MB) loads inside
  `system_server`; log line `LlamaEngine: llama.cpp native library loaded`.
- **For the talk:** about 400 source files compiled first time; all four
  failures were about how the build system names and finds things, none
  about the code.

## 26. Three bugs that would have stopped any model calling a tool

- **When:** 5 Oct 2026, about 21:00, found by reading the agent code while
  writing the engine layer, before any model had run.
- **What was wrong:**
  1. **The planner never told the model which tools exist.** It passed
     `null` for the tool list and then looked for a tool call in the reply.
  2. **The first step skipped planning.** A new session was created with
     status `"PLANNING"`, and the router only sends a session to the planner
     while its status is *not* `"PLANNING"`, so every query went straight to
     "respond".
  3. **The engine's reply was treated as the answer.** Cactus returns a JSON
     envelope (`{"success":..., "response":"...", "function_calls":[...]}`);
     the code showed the whole envelope to the user as the answer, and the
     tool path looked for a key (`tool_call`) the engine never sends.
- **Before** (`agent/PlanNode.java`, `agent/JarvisExecutor.java`):

  ```java
  return CactusWrapper.complete(modelHandle, messages.toString(), userFacts, null);
  ...
  s.status     = "PLANNING";
  ```
- **After:** the planner asks the dispatcher for the tools worth offering and
  passes them; a new session starts as `"NEW"`; one class
  (`inference/Completion.java`) reads the envelope for both engines:

  ```java
  String toolsJson = mToolDispatcher != null
          ? mToolDispatcher.toolsJsonFor(session.originalQuery) : null;
  Completion completion = Inference.completeWithTools(
          modelHandle, buildMessages(session).toString(), userFacts, toolsJson);
  ...
  s.status     = "NEW";
  ```
  The planner also now sends earlier tool calls and their results back to the
  model, so it answers from the result instead of calling the tool again.
- **How it was missed:** the router's unit tests passed. One test set the
  status to `"NEW"` by hand to check the planning route, which is not what the
  real code did.
- **Proven to:** emulator, with a real model (entry 28 has the transcript).
- **For the talk:** these were in the code for months and no test could find
  them, because nothing had ever run a model. All three would have appeared
  together on the first day with the phone.

## 27. No way to put a model on the device

- **When:** 5 Oct 2026, 21:30. The engine was loaded and wanted
  `/data/system/jarvis/models/chat.gguf`.
- **What happened:** `adb push` there: `Permission denied`. `adb root`:
  `ADB Root access is disabled by system setting`. Only the system may write
  to that folder, which is the point of it.
- **Fix:** let the service receive the file itself:
  `adb shell -T cmd jarvis install-model chat < model.gguf`. The service
  writes it to its own folder and reloads. 1.8 GB took 50 seconds. (A first
  try with `adb exec-in` copied the file but printed nothing, which looked
  like a failure.)
- **Proven to:** emulator.
- **For the talk:** "copy the model to the phone" was a line in the plan with
  nothing behind it. This also works on a phone without root.

## 28. "How much battery do I have?" got a lecture instead of an answer

- **When:** 5 Oct 2026, 21:36, the first set of plain-English questions to a
  real model (Qwen3 1.7B through llama.cpp, on the emulator).
- **Expected:** the built-in `get_battery_status` tool is called.
- **What happened:** four of five questions worked (set an alarm, set it
  again, list alarms, delete it). The battery question got three paragraphs
  on how to find the battery icon on phones, laptops and smartwatches.
- **Cause:** tools are found by meaning: each tool's description is turned
  into a vector when it is registered, and the closest ones are offered to
  the model. Tools are registered at start-up, before any model is loaded, so
  the 14 built-in tools had no vectors. Only the alarm app, installed later,
  was searchable. Nothing to do with llama.cpp: Cactus would have behaved the
  same.
- **Fix** (`tools/ToolScannerService.java`): when an embedding model is set,
  index every tool already registered.
- **After the fix:** "Your battery is at 100% and it is currently charging."
  Still wrong afterwards: "Turn the flashlight on" was answered "the provided
  tools do not include a function to turn the flashlight on", because
  `set_flashlight` was not among the five closest tools the small embedding
  model picked. Open.
  **Corrected on 8 Oct 2026: that explanation was wrong, see entry 29.**
- **Proven to:** emulator (19 of 19 app checks and 22 of 22 tool checks with
  the model loaded).
- **For the talk:** the first real answer JarvisOS ever gave was "The capital
  of France is Paris." at 21:35 on 5 Oct 2026, 24 seconds after being asked.
  Screenshot: `assets/2026-10-05-jarvis-app-chat-with-model.png`.

## 29. The flashlight was never a tool

- **When:** 8 Oct 2026, 17:15, while collecting the real tool list to try
  Needle with.
- **Expected:** `set_flashlight` is one of the built-in tools; entry 28 said
  the search simply ranked it sixth or lower.
- **What happened:** `cmd jarvis tool set_flashlight '{"enabled":true}'`
  answered `Error: tool 'set_flashlight' not registered`. The code that
  switches the torch existed in `SystemToolExecutor`, but a built-in tool is
  only registered if it has a JSON file in `vendor/jarvisos/tools/`, and there
  were 14 files for 16 tools. `set_flashlight` and `set_brightness` had none.
- **Cause:** Claude's explanation in entry 28 was a guess that was never
  checked. No model, small or large, could have picked a tool that was not in
  the registry. On 5 Oct the chat model's reply ("the provided tools do not
  include a function to turn the flashlight on") was simply true.
- **Fix:** two new files. Before: nothing. After
  (`tools/set_flashlight.json`):

  ```json
  {
    "package_name": "com.jarvisos.system",
    "app_label": "JarvisOS System",
    "receiver_class": "@system/set_flashlight",
    "tool_name": "set_flashlight",
    "description": "Turn the flashlight (torch) on or off. Use when user says turn on the flashlight, torch on, switch off the flashlight, turn the light off, or I need a light.",
    "params": [
      {"name": "enabled", "type": "boolean", "required": true, "description": "true to turn the flashlight on, false to turn it off"}
    ]
  }
  ```

  The tool also said "Flashlight on" without knowing. Before
  (`tools/SystemToolExecutor.java`):

  ```java
  cm.setTorchMode(torchCameraId, enable);
  return "Flashlight " + (enable ? "on" : "off");
  ```

  After, it waits for the camera service to report the change:

  ```java
  cm.registerTorchCallback(callback, BackgroundThread.getHandler());
  try {
      cm.setTorchMode(cameraId, enable);
      if (!changed.await(TORCH_CONFIRM_MS, TimeUnit.MILLISECONDS)) {
          return "Error: the flashlight did not turn " + (enable ? "on" : "off");
      }
  } finally {
      cm.unregisterTorchCallback(callback);
  }
  return "Flashlight " + (enable ? "on" : "off");
  ```

- **Proven to:** emulator. `dumpsys media.camera` logs "Torch for camera id 0
  turned on for client PID" (system_server) after each request; the emulator's
  camera has a flash unit but no light to look at.
- **For the talk:** a failure blamed on the model for three days was a
  missing 9-line file. Check the registry before blaming the model.

## 30. An engine with no build for the emulator

- **When:** 8 Oct 2026, 17:20 to 17:27.
- **Expected:** Needle 3 and Whistle run from a prebuilt engine
  (`libneedle.a`). It is published for 15 platforms; android-x86_64, the
  emulator, is not one of them, so the plan on 5 Oct was "phone only".
- **What happened:** the linux-x86_64 library was linked into an Android
  library anyway. `llvm-nm -u` showed it needs only libc, libm and libc++, no
  sockets. The first link failed on exactly two symbols:

  ```
  ld.lld: error: undefined symbol: bcmp
  ld.lld: error: undefined symbol: _ZNSt3__113__hash_memoryEPKvm
  ```

- **Cause:** the library was built against glibc and a newer libc++ than
  Android 15 ships. Android's C library dropped `bcmp`, and
  `std::__hash_memory` only exists in libc++ from LLVM 21.
- **Fix** (`native/needle/glibc_shim.cpp`, built for x86_64 only): supply
  them, plus the three glibc character tables and `__isoc99_fscanf`.

  ```cpp
  int bcmp(const void* a, const void* b, size_t n) { return memcmp(a, b, n); }

  size_t needle_shim_hash_memory(const void* data, size_t n)
          __asm__("_ZNSt3__113__hash_memoryEPKvm");
  ```

- **Proven to:** emulator. The engine gives the same answer and the same
  confidence to four decimal places inside `system_server` as the published
  runner does on the server (0.7269 for the same tool list). The arm64 build
  for the phone uses the proper android-arm64 library and needs no shim.
- **For the talk:** "not supported on the emulator" turned out to mean six
  missing symbols.

## 31. Confidence that moved when the list was reordered

- **When:** 8 Oct 2026, 17:33, the first request through Needle inside the
  service.
- **Expected:** "turn on the flashlight" scores about 0.97, as it had on the
  server ten minutes earlier, and runs at once.
- **What happened:** it scored 0.7269, under the bar of 0.75, so the request
  went to the chat model and took 22 seconds instead of a quarter of one.
- **Cause:** the same 16 tools in a different order. The server test listed
  them alphabetically; the service listed them in the order they were
  registered. Over eight random orders the right tool was always chosen, but
  confidence for a correct call ranged from 0.55 to 0.98, and wrong calls for
  "what is the capital of France" reached 0.67.
- **Fix** (`tools/ToolDispatcher.java`): a fixed order, so the score cannot
  change because an app was installed earlier or later.

  ```java
  all.sort((x, y) -> String.valueOf(x.toolName).compareTo(String.valueOf(y.toolName)));
  ```

- **Proven to:** emulator (29 of 29 in `tests/emulator/voice.sh`). Not solved:
  the gap between the highest wrong score seen (0.67) and the bar (0.75) is
  small, and some correct requests ("set an alarm for 7:30", 0.55 to 0.80)
  fall under the bar and take the slow path. Cactus's own answer is
  fine-tuning Needle on the product's tools; not tried.
- **For the talk:** a confidence score is a measurement of the model and the
  prompt together. Change the prompt and it is a different instrument.

## 32. The robot voice Jarvis could not understand

- **When:** 8 Oct 2026, 17:41, first full run of `voice.sh`.
- **Expected:** all six spoken clips switch the torch.
- **What happened:** five did. The sixth, "Turn off the flashlight." spoken
  by the robotic `espeak-ng` synthesiser, was heard as "to an offensive last
  night". Needle was not confident about it, so the transcript went to the
  chat model, which asked what the user meant. Nothing was switched.
- **Cause:** a small speech model and an unnatural voice. The two natural
  synthetic voices (piper) were heard correctly every time.
- **Fix:** none to the product; the safe thing happened. The test now reports
  robotic clips instead of judging them.
- **Proven to:** emulator, with synthetic voices only. No human voice and no
  real microphone has been tried: the emulator runs without audio.
- **For the talk:** the useful result is the failure mode. A misheard request
  did nothing, slowly, rather than the wrong thing, quickly.

## 33. The phone build was harder than the emulator hack

- **When:** 8 Oct 2026, 17:45 to 17:57, compiling the Needle library for the
  phone (arm64) for the first time.
- **Expected:** the emulator needed a shim because it borrows a Linux
  library; the phone has a proper android-arm64 library, so it just links.
- **What happened:** four attempts. (1) The device is `Pong`, not `pong`.
  (2) The phone build refuses a package list that names a module which does
  not exist for it (`libneedle_jarvis_emu`); the emulator build had accepted
  the reverse. (3) `jni.h` not found. (4) Dozens of
  `undefined symbol: std::__ndk1::...`.
- **Cause of (4):** the engine was built with the NDK, whose C++ library
  lives in the namespace `std::__ndk1`; the platform's lives in `std::__1`.
  Asking Soong for the NDK one (`stl: "c++_static"`) did not supply those
  symbols in this tree.
- **Fix:** list one module per target (`jarvisos.mk`):

  ```make
  ifneq ($(filter %_x86_64,$(TARGET_PRODUCT)),)
  PRODUCT_PACKAGES += libneedle_jarvis_emu
  else
  PRODUCT_PACKAGES += libneedle_jarvis
  endif
  ```

  and link the toolchain's own NDK C++ library, copied beside the engine
  (`prebuilts/needle/android-arm64/libc++_static.a`, `libc++abi.a`, 28 MB):

  ```
  static_libs: [
      "libneedle_ndk_libcxx_arm64",
      "libneedle_ndk_libcxxabi_arm64",
  ],
  ```

- **Proven to:** compiles (`libneedle_jarvis.so` for Pong, 2.0 MB). It has
  never been loaded: no arm64 device. The full Pong ROM has not been rebuilt.
- **For the talk:** the "hack" for the emulator took two tries; the
  "supported" platform took four.

## 34. "Right tool" was not "it worked": the shell ran tools as the shell

- **When:** 17 Oct 2026, comparing Qwen3-1.7B and Gemma 4 E2B on the
  emulator with `tests/emulator/compare-models.sh` (12 requests each, fast
  path off).
- **Expected:** the first version of the script scored only the tool name.
  Qwen got 12 of 12.
- **What happened:** the replies did not match the score. "Turn off do not
  disturb" answered "DND has been set to total silence", and alarm,
  brightness and open-app answered with errors. Recording the arguments and
  the tool's own result showed two separate faults:
  1. Model: Qwen called `set_dnd {"mode":"total_silence"}` for "turn off".
     Gemma called `{"mode":"off"}`, which is correct.
  2. Ours: three tools returned
     `Permission Denial: package=android does not belong to uid=2000`
     with both models. uid 2000 is the shell.
- **Cause of 2:** the Binder entry points clear the caller's identity before
  tools run; the `cmd jarvis` shell commands do not, so tools ran as the
  shell, which may not act as the system package.

  ```java
  // processQuery (Binder): tools run as the system
  final long token = Binder.clearCallingIdentity();
  try { return runQuery(query); } finally { Binder.restoreCallingIdentity(token); }

  // onCommand "query" (shell): tools run as uid 2000
  pw.println(runQuery(getNextArgRequired()));
  ```

- **Fix (17 Oct, same afternoon):** the shell `query`, `tool` and `voice`
  commands now clear the identity as the Binder methods do:

  ```java
  case "query": {
      final String text = getNextArgRequired();
      final long token = Binder.clearCallingIdentity();
      try { pw.println(runQuery(text)); } finally { Binder.restoreCallingIdentity(token); }
      return 0;
  }
  ```

  The first build of it failed: `variable token is already defined in method
  onCommand(String)`. The cases of a Java `switch` share one scope, and the
  old `query` case had no braces of its own.
- **Proven to:** emulator. From the shell, `set_brightness`, `set_alarm` and
  `open_app` now succeed (brightness reads 77/255 = 30%). The Binder path
  (the Jarvis app) was never affected.
- **For the talk:** a score of 12/12 hid one wrong argument and three
  failures of our own. Score what happened, not what was called.

## 35. "How are you doing today" read the notifications

- **When:** 17 Oct 2026, 15:15, Kevin chatting to Jarvis in the app on the
  emulator (scrcpy), minutes after the Needle dry run of roadblock 34's
  12 requests had shown no wrong guess above the bar.
- **Expected:** small talk scores low with Needle and goes to the chat model.
  In the dry run, the two general questions scored 0.50 and 0.52.
- **What happened:** from the log:

  ```
  processQuery: how are you doing today
  FastPath: decided in 576 ms, confidence 0.8043: get_notifications {}
  SystemToolExecutor: Executing @system/get_notifications
  processQuery: notifications ?
  FastPath: decided in 583 ms, confidence 0.7759: get_notifications {}
  ```

  Needle was sure enough to pass the bar (75, `FastPath.java:22`), so the
  tool ran and the chat model never saw the message.
- **Cause:** Needle always names a tool. Its confidence is a score of that
  guess, not of "this is a command". Twelve dry-run requests were too few to
  set the bar.
- **Fix:** not made yet.
- **Proven to:** seen in the app by a human, first try.
- **For the talk:** the test said "safe"; the first real user said
  "how are you" and Jarvis read their notifications.

## 36. Apps in the ROM had no tools after boot

- **When:** 17 Oct 2026, 19:32, first boot of the image with the tic-tac-toe
  game (the first tool app ever built into the ROM rather than installed
  with `adb install`).
- **Expected:** `cmd jarvis tools` lists the game's four tools.
- **What happened:** only the 16 built-in tools. The log:

  ```
  19:32:33.418 ToolScannerService: Initial scan — found 0 tool receiver(s)
  19:32:33.420 JarvisSessions: Session declared: com.jarvisos.tictactoe/tic_tac_toe ...
  19:32:36.055 keystore2: on_device_unlocked(user_id=0, ...)
  ```

  Ten minutes later, `cmd package query-receivers -a com.jarvisos.TOOL`
  listed all four receivers.
- **Cause:** the scan runs while `system_server` starts, before the user is
  unlocked. Until then a package query only returns components that are
  "direct boot aware", and no tool app is. The session manifest was found in
  the same second because it was read through `getInstalledApplications`,
  which still sees the app. This has been there since the port; the alarm
  demo never showed it because `app-tools.sh` installs it after boot, which
  goes through the "package added" path instead.
- **Before** (`services/jarvis/.../tools/ToolScannerService.java`):

  ```java
  List<ResolveInfo> resolved = mPackageManager.queryBroadcastReceivers(
          queryIntent, PackageManager.GET_META_DATA);
  ```

- **After:**

  ```java
  private static final int SCAN_FLAGS = PackageManager.GET_META_DATA
          | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
  ...
  List<ResolveInfo> resolved = mPackageManager.queryBroadcastReceivers(
          queryIntent, SCAN_FLAGS);
  ```

- **Proven to:** emulator. After the rebuild, `cmd jarvis status` right after
  boot shows 2 apps and 20 tools.
- **Commit:** frameworks_base `a9ab7a3f797c` (with the session work); the JNI changes of entry 37 are llama.cpp `4b621acd5`.
- **For the talk:** every app we had tested was installed after the phone
  was up. The first one shipped inside the ROM was invisible until it was
  reinstalled. On a phone, that would have been every preinstalled app.

## 37. "Required" did not mean "no talking"

- **When:** 17 Oct 2026, 19:40 to 20:24, the first turns of Jarvis in the
  game (Qwen3-1.7B, llama.cpp, emulator).
- **Expected:** with llama.cpp's grammar on and `tool_choice` set to
  "required", the reply can only be tool calls.
- **What happened:** three versions in a row:
  1. `tool_choice: auto`: the first turn answered in words, "Let's see if I
     can win! I'll play in cell 5.", and called nothing (18.6 s). The retry
     moved.
  2. `tool_choice: required`: words still came first, then the call. Once
     the words were "I'll play in cell 5 to complete my move! 🎮 ... 🎯. 🎯.
     🎯. 🎯." until the 160-token limit, with no call at all: 25.7 s wasted.
  3. A new option, `calls_only`, meant to start the reply with the
     template's tool-call marker. It changed nothing.
- **Cause:** llama.cpp builds the grammar for Qwen3's template with its
  "auto parser", which allows content before the calls even when calls are
  required (`common/chat-auto-parser-generator.cpp`):

  ```cpp
  return ctx.reasoning_parser + p.optional(p.content(content_before_tools)) + tool_calls + p.end();
  ```

  Version 3 looked for the marker among the grammar's trigger words, but the
  auto parser sets trigger words only for the lazy grammar (`tool_choice`
  auto), so for "required" there were none.
- **Fix** (`vendor/llama.cpp/jarvisos/llama_jni.cpp`): take the marker from
  the same request rendered with `tool_choice` auto, feed it to the model
  and the grammar as the start of the reply, and let the grammar continue:

  ```cpp
  common_chat_templates_inputs lazy_inputs = inputs;
  lazy_inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
  const common_chat_params lazy =
          common_chat_templates_apply(engine->templates.get(), lazy_inputs);
  for (const common_grammar_trigger& trigger : lazy.grammar_triggers) { ... forced = trigger.value; ... }
  ...
  for (llama_token token : marker) common_sampler_accept(constrained, token, true);
  if (!decode_all(engine, marker)) { ... }
  text = forced;
  ```

  In the same change the JNI keeps the last prompt in the model's cache and
  reuses the part the next prompt shares (`reused_tokens` in the trace).
- **Result:** no more words before calls; a turn went from 20 to 36 s to
  11 to 22 s, and the second call of a turn from about 11 s to 3.5 s
  (760 of 853 prompt tokens reused). The side effect: Jarvis stopped
  talking, because the words had been its only speech. It now calls `say`
  by itself sometimes, and otherwise gets one short say-only call after
  its move (entry 40).
- **Proven to:** emulator, `tictactoe.sh` 34 of 34 (run 3, 20:24).
- **For the talk:** "constrained decoding" sounds absolute. In practice it
  is whatever grammar the library writes for that model's template, and you
  have to read it.

## 38. The tool did the maths; the model misread it (open)

- **When:** 17 Oct 2026, 19:58, game 1 of the second full run.
- **What happened:** `evaluate_moves` (a full minimax search in the game)
  returned:

  ```
  {"0":"draw","1":"loses: player wins in 3 moves","2":"draw","3":"loses: player wins in 3 moves", ...}
  ```

  and Qwen3-1.7B replied "playing in cell 1, 2, 3, 6, or 8 will result in a
  draw. Playing in cells 0, 5, or 7 will result in the player winning ...
  I'll choose to play in cell 1." It then lost games it had the table to
  draw. In the third run it lost all three games, with the tool on and off.
- **Cause:** a 1.7B model reading a nine-entry table. The tool was right
  every time (checked by `BoardTest`).
- **Change (not a fix):** the tool now also says which cells are best,
  `{"best":[4],"cells":{...}}`, so the model only has to copy one number.
  The idea from the plan, "the tools do the calculation, the model decides",
  moved further towards the tool.
- **Proven to:** emulator, and only as far as this: with `best` added,
  Jarvis drew all three games it played with the tool on (runs 4 and 5, from
  the button and from Jarvis) and lost the game with the tool off. Before
  `best` it lost all three. The model runs at temperature 0 and the test's
  player always answers the same way, so these are the same game repeated,
  not a sample. The model still says odd things ("I played cell 1" lines
  that read the tool's reply back).
- **For the talk:** this is the on/off experiment's real answer so far: the
  tool can be perfect and the result still depends on whether the model can
  read it. Same model, same game, try it live.

## 39. Half an emoji stopped the test

- **When:** 17 Oct 2026, 20:01, game 2 of the second full run.
- **What happened:** the game test stopped moving for 20 minutes after a
  turn that had worked. Jarvis's line ended in a row of 🔥, cut to 160
  characters: `...🔥🔥🔥���…`.
- **Cause:** `say` cut the text with `substring(0, 159)`, which counts
  UTF-16 units; an emoji is two, so the cut split one. The broken character
  went to the screen, and `uiautomator dump` then failed on every try, so
  the test could not read the board. The test also waited by reading the
  screen, so it waited forever.
- **Before** (`session/SessionTools.java`):

  ```java
  return text.length() <= MAX_SAY_CHARS ? text : text.substring(0, MAX_SAY_CHARS - 1) + "…";
  ```

- **After:**

  ```java
  text = text.replaceAll("</?think>", "").trim();   // Qwen also leaked "</think>"
  if (text.codePointCount(0, text.length()) <= MAX_SAY_CHARS) return text;
  return text.substring(0, text.offsetByCodePoints(0, MAX_SAY_CHARS - 1)) + "…";
  ```

  The test now waits on `cmd jarvis session` (is a turn running?) and reads
  the screen only after.
- **Proven to:** unit test (`sayCutsWholeCharacters`) and emulator (no stall
  in the runs after).
- **For the talk:** a model's output is user input. It reaches the screen,
  and the screen has rules too.

## 40. Smaller ones from the tic-tac-toe day

- **A prediction, wrong again.** "Service-only changes take about 4
  minutes." The session API is new framework API (`android.jarvis`), and the
  first image build took 40 min 40 s (4,800 build steps; the boot classpath
  changed). Later service-only builds took 2 to 3.5 minutes, as before.
- **`/data` wiped by the image build.** The same build regenerated
  `userdata.img`, so the emulator came up without its four models (as on
  8 Oct). Reinstalled with `cmd jarvis install-model`.
- **The test could not see the screen while Jarvis thought.** The
  "thinking" spinner never lets the screen go idle, so `uiautomator dump`
  fails during a turn and leaves the last file behind. The first full run
  then read an empty board and sent `input tap` with no coordinates.
  The test now deletes the old dump, retries, and waits on the service.
- **`pkill -f tictactoe.sh` killed Claude's own shell**, whose command line
  contained the same words. The same trap as `pkill -f qemu` on 5 Oct.
- **Editing a test while it ran.** Bash reads a script as it goes; a
  change made to `tictactoe.sh` during run 4 ended it with "syntax error
  near unexpected token" in the middle of game 2. The script was fine.
- **A real alarm.** In run 5 the test's first tap missed the "Play with
  Jarvis" button (the screen was not readable in the first minute after
  boot), so no session was open, and the fence check
  `cmd jarvis tool set_alarm '{"hour":7,"minute":0}'` was not refused: it
  set a real 07:00 alarm on the emulator ("Alarm set for 07:00 — Jarvis
  Alarm"). The fence did its job (no session, so no fence); the test was
  wrong to call a tool with side effects without checking first. It now
  stops before those calls if no session is open, and waits up to 30 s for
  the button.
- **A wrong unit test.** `BoardTest` expected "wins now" for cell 2 of
  `OO.XX.X..`; the code said "wins now, blocks the player", which is right:
  cell 2 also stops X's 2-4-6 diagonal.
- **Jarvis went quiet** once only calls were allowed (entry 37). The turn
  now ends with one short call that may only `say`, when the model has not
  said anything. With the prompt cached it costs a few seconds, after the
  move is already on the board.



## 41. The rules lived in notes, not in the tools (the harness)

- **When:** 22 Oct 2026, 11:00–13:20 CEST. Kevin had seen people doing
  "harness engineering" around Opus to reverse-engineer apps and asked for one
  for JarvisOS ("write it well, run it in tmux").
- **Expected:** the edit → build → boot → test loop was already scripted.
- **What was there instead:** eight scripts in `tests/emulator/`, three in
  `~`, and rules kept only in Claude's memory notes, each one learned from an
  incident: never `lunch` (a 4-minute build became a full 62k-step one,
  17 Oct), never `pkill -f qemu` (it kills the shell that runs it, 5 Oct),
  never tap the emulator while Kevin watches on scrcpy (the game looked like
  it played itself, 17 Oct), never `ln` a ROM zip (a backup was overwritten,
  2 Oct), never push without Kevin. A session that did not read the notes
  could break every one of them.
- **Cause:** a rule in a note depends on someone reading it. Nothing in the
  tools enforced it.
- **Before** (memory note `jarvisos-agent-experience.md`):

  ```
  It went full because Claude once used `lunch lineage_sdk_phone_x86_64-ap4a-userdebug`
  instead of the scripts' `breakfast sdk_phone_x86_64 userdebug`; always use breakfast.
  ```

- **After** (`harness/hooks/guard.py`, a Claude Code hook that runs before
  every shell command Claude sends):

  ```python
  (CMD + r"lunch\b",
   "deny",
   "Use `breakfast sdk_phone_x86_64 userdebug` (or `jh build`), never `lunch`: "
   "on 17 Oct 2026 `lunch lineage_sdk_phone_x86_64-...` turned a 4-minute build into a full 62k-step one."),
  ```

  Plus `jh`, one command for the loop (`jh build`, `jh emu restart`,
  `jh test fast`, `jh logs`, `jh watch on`). Every command ends in one line,
  `jh: OK ...` or `jh: FAIL ...`, with logs and JSON in `~/.jh/runs/`.
- **Proven to:** server (`hooks/test_guard.py`, 38 checks) and emulator
  (`jh test fast`, 4/4 suites: host 88, history 46, jarvis-app 19,
  tic-tac-toe quick 32).
- **Commit:** vendor_jarvisos `4ce9195`.
- **For the talk:** the agent did not need to get smarter. It needed tools
  that say no, and an answer it can read in one line.

## 42. Cactus could not run on the test phone at all

- **When:** 22 Oct 2026, 11:00 CEST.
- **Expected:** Kevin asked what it would take to run Cactus on the emulator.
- **What was there:** the emulator is x86_64. Cactus's maths code
  (`cactus-kernels`, about 65,000 lines across kernels, graph and engine) is
  written directly in ARM NEON intrinsics: 233 different ones in 17 files,
  with no plain C fallback. Upstream's CMake refuses any ABI but arm64, and
  our `Android.bp` copied that. So since 2 Oct the emulator had run Jarvis
  only on llama.cpp; the Cactus engine class had never run anywhere.
- **Options weighed:** an arm64 emulator on this x86 server (no longer
  supported for system images); an ARM cloud server (needs KVM on ARM
  hardware, costs money); `qemu-user` on the server (tests the library, not
  the service); or translate NEON to x86. Chosen: translate with SIMDe
  (header-only, MIT), so Cactus's code compiles unchanged.
- **Before** (`vendor/cactus/Android.bp`):

  ```
  // arm64 only, like upstream (its CMake FATAL_ERRORs on any other ABI).
  ...
  arch: {
      arm64: {
          enabled: true,
      },
  },
  ```

- **After:**

  ```
  arch: {
      arm64: {
          enabled: true,
          cflags: ["-march=armv8.2-a+fp16+simd+dotprod+i8mm"],
      },
      x86_64: {
          enabled: true,
          local_include_dirs: ["jarvisos/x86"],
          ...
  ```

  and `jarvisos/x86/arm_neon.h`, which stands in for the compiler's header
  on x86 only:

  ```c
  #define SIMDE_ENABLE_NATIVE_ALIASES
  #include "simde/arm/neon.h"
  #include "neon_aliases.h"
  ```

- **What it changes on the phone:** nothing. The x86 files are only on the
  x86 include path, and the arm64 flags are the same as before.
- **Proven to:** server tests (Cactus's own 16 kernel and graph test
  programs, built for x86 through SIMDe: every correctness check passes;
  `test_matmul` and `test_nn` ran into the 10-minute limit in their benchmark
  sections, after all their checks had passed) and ROM builds (`libcactus.so`
  for the emulator: 3.2 MB, 10 JNI entry points, no network imports) and
  **emulator** (22 Oct, 23:45 CEST): `libcactus.so` loads in `system_server`,
  Gemma 4 E2B CQ4 loads in 1.4 s, answers a question in 15 s and, after
  entry 50, turns on the torch through a tool call (camera service: "Torch
  for camera id 0 turned on"). That call took 8 min 56 s: translated NEON
  and a prompt with 20 tools. Speed means nothing here.
- **For the talk:** the emulator can only prove the Cactus path is correct.
  Translated NEON is slow, so speed questions wait for the phone. Say so.

## 43. `__fp16` is not a real number on x86

- **When:** 22 Oct 2026, 13:25 CEST, first compile through SIMDe.
- **What happened:** hundreds of errors in the first file:

  ```
  cactus_kernels.h:119:8: error: function return value cannot have __fp16 type; did you forget * ?
  scalar.cpp:16:37: error: parameters cannot have __fp16 type; did you forget * ?
  ```

- **Cause:** on ARM, `__fp16` is a full half-precision number. On x86, clang
  allows it only as a storage format (in memory, never passed or returned).
  Cactus passes it by value everywhere. `_Float16` is the C standard type
  that x86 clang treats as a number, and SIMDe already uses it.
- **Fix** (x86_64 cflags only): `-D__fp16=_Float16 -Wno-keyword-macro`.
- **Proven to:** server tests (entry 42).
- **For the talk:** the same word, `__fp16`, meant two different things on
  two chips.

## 44. Translated code that broke on two ordinary C++ habits

- **When:** 22 Oct 2026, 13:30 CEST.
- **What happened:** after entry 43, five files still failed:

  ```
  matmul.cpp:2870:25: error: too many arguments provided to function-like macro invocation
   2870 |         (int8x8_t){0,-2,-4,-6, 0,-2,-4,-6},
  neon.h:12211:11: note: macro 'vcombine_s8' defined here
  scalar.cpp: error: use of undeclared identifier 'vsqrtq_f32'
  ```

- **Cause:** SIMDe gives each NEON name as a function-like macro,
  `#define vcombine_s8(low, high) simde_vcombine_s8((low), (high))`. Two
  ordinary C++ habits break that: the commas inside a vector literal split it
  into too many macro arguments, and passing an intrinsic as a value
  (`apply_f32_op_on_f16x8(v, vsqrtq_f32)`) has no parentheses, so the macro
  never expands.
- **Before** (Cactus, `scalar.cpp`, left unchanged):

  ```cpp
  [](float16x8_t v) { return apply_f32_op_on_f16x8(v, vsqrtq_f32); },
  ```

- **After:** Cactus's code is untouched. A generated header,
  `jarvisos/x86/neon_aliases.h`, replaces all 4,117 SIMDe aliases with plain
  names:

  ```c
  #undef vsqrtq_f32
  #define vsqrtq_f32 simde_vsqrtq_f32
  ```

- **Proven to:** compiles (all 56 sources), server tests (entry 42).
- **Commit:** cactus `530516c`.
- **For the talk:** fix it where you own the code. Patching 17 upstream
  files would have to be redone at every Cactus release.

## 45. The server test could not see an Android-only header

- **When:** 22 Oct 2026, 22:35 CEST, first Soong build of `libcactus` for x86.
- **What happened:**

  ```
  vendor/cactus/cactus-kernels/src/threading.h:11:10: fatal error: 'asm/hwcap.h' file not found
  ```

- **How it was missed:** the server-side test built for Linux, not Android.
  The include sits under `#if defined(__ANDROID__)`, so only the real
  Android build reached it.
- **Cause:** `<asm/hwcap.h>` is an arm64 kernel header. Cactus includes it on
  every Android build, but only uses it inside its `__aarch64__` branch.
- **After:** an empty `jarvisos/x86/asm/hwcap.h` on the x86 include path.
- **Proven to:** ROM builds.
- **For the talk:** a test that is not the real build tests less than you
  think. Each test layer here missed something the next one caught.

## 46. A warning became a link error

- **When:** 22 Oct 2026, 22:38 CEST, the next build.
- **What happened:** everything compiled; the link failed:

  ```
  ld.lld: error: vendor/cactus/jarvisos/x86/simde/arm/neon.h:74992:5: loop not vectorized:
  the optimizer was unable to perform the requested transformation
  ```

- **Cause:** SIMDe marks its fallback loops "please vectorize"
  (`#pragma clang loop vectorize(enable)`). When the optimizer cannot, it
  emits a remark. Android links with ThinLTO (it optimizes again at link
  time) and `-Wl,--fatal-warnings`, so the remark became an error at link
  time.
- **After** (x86_64 block only): `lto: { never: true }`.
- **Proven to:** ROM builds (`libcactus.so` for the emulator).
- **For the talk:** the platform's strict defaults are there for good
  reasons. Switch them off narrowly (one library, one architecture) and write
  down why.

## 47. There was no way to install a Cactus model (found for the phone)

- **When:** 22 Oct 2026, 22:45 CEST, getting ready to load Gemma 4 on Cactus.
- **What we found:** `cmd jarvis install-model` stores what it reads on
  standard input as one file. Cactus v2 loads a folder (a "bundle":
  `config.txt` plus about 2,000 weight files; the Gemma 4 E2B CQ4 zip is
  2.4 GB, 4 GB unpacked). So on phone day, with the old command, Cactus would
  have had no model at all.
- **How it was found:** not by a test, but by reading the code to plan the
  emulator test. The emulator work found a phone problem before the phone
  arrived.
- **Before** (`JarvisService.java`):

  ```java
  if (bytes == 0 || !tmp.renameTo(target)) {
  ```

- **After:**

  ```java
  if (!small && isZip(tmp)) {
      // A Cactus bundle: a zipped folder with config.txt, unpacked
      // so that target is the folder itself.
      try {
          installBundle(tmp, target);
  ```

  `installBundle` drops the zip's top folder, refuses entries that would land
  outside the bundle folder, and swaps the new bundle in only once it is
  complete.
- **Proven to:** emulator: the 2.7 GB zip went in through
  `adb shell -T cmd jarvis install-model chat < gemma-4-e2b-it-cq4.zip` in
  77 s and Cactus loaded it ("model primary: ready").
- **For the talk:** a feature counted as "supported" because the engine class
  existed, but it had never had a model to load.

## 48. "auto" would pick an engine with no model (open, phone)

- **When:** 22 Oct 2026, 22:50 CEST.
- **What we found:** `persist.sys.jarvis.engine` defaults to `auto`, and
  `auto` takes Cactus whenever `libcactus.so` loads, whether or not a Cactus
  model is installed. On the emulator this was about to switch Jarvis away
  from its working llama model the moment the new library arrived. On the
  phone, someone who installs only the GGUF would get an engine with nothing
  to answer with.
- **Emulator fix (written):** `InferenceEngine.suitsAuto()`. Cactus answers
  false on x86, so the emulator stays on llama unless Cactus is asked for by
  name.
- **Phone fix (written the same evening, not yet run anywhere):** `auto`
  first takes an engine that can run and has a chat model installed, then
  one that can only run:

  ```java
  if (engine.suitsAuto() && new java.io.File(engine.modelPath("chat")).exists()) {
  ```

  The emulator cannot exercise it (Cactus is never `auto` there), so it
  waits for the phone.
- **For the talk:** a default that was right with one engine became wrong
  the day there were two.

## 49. Smaller ones from the Cactus day

- **The harness blocked its own author.** Writing these notes, Claude's
  command contained the text of the `lunch` rule, and the new hook refused
  it: the rule looked for the word anywhere, not where a command starts.
  Fixed: every rule now anchors on a command position, and
  `hooks/test_guard.py` (38 checks) includes text that only mentions
  `lunch`, `pkill` and `git push`. A guard that fires on words, not actions,
  gets switched off; this one needed to be precise.
- **`pkill -f` killed Claude's own shell, a third time.** To clear an old
  test run, Claude ran `pkill -f 'jarvisos/x86/host-test.sh'` in a command
  line that contained the same path; the shell matched and died (exit 144).
  The commit before it had already gone through. Same trap as 5 Oct and
  17 Oct.
- **The harness reported the wrong build.** `jh build wait` picked the
  newest summary file, but each build deletes its own summary when it
  starts, so it found the Pong ROM summary of 2 Oct and said "OK" for a build
  that had failed. Fixed: `jh build` writes down which summary to wait for.
  The harness's first real job found a bug in the harness.
- **"Failed" tests that had passed.** The x86 kernel tests reported
  `test_matmul` and `test_nn` as failed (exit 124). Every check had passed;
  the 10-minute limit hit in the benchmark sections that run afterwards.

## 50. Cactus had never been given a tool

- **When:** 22 Oct 2026, 23:47 CEST, the first tool request to Gemma 4 on
  Cactus (emulator).
- **Expected:** "turn on the flashlight" → a `set_flashlight` call, as on
  llama.cpp.
- **What happened:** it answered in prose, as if it had no tools:

  ```
  As a large language model, I do not have the ability to control physical devices or access hardware like a flashlight.
  ```

- **Cause:** Cactus's tool parser (`parse_tools_json` in
  `cactus-engine/src/utils.h`) is hand-written string search. It finds each
  tool by looking for the key `"function"`:

  ```cpp
  pos = json.find("\"function\"", pos);
  ```

  JarvisOS sends bare definitions (`{"name":..., "parameters":...}`), so
  Cactus skipped every one. llama.cpp's JNI accepted both shapes, so in five
  weeks nothing noticed. On the phone, Cactus would never have called a
  single tool.
- **Before** (`inference/CactusEngine.java`):

  ```java
  return CactusWrapper.complete(modelHandle, messagesJson, null, toolsJson);
  ```

- **After:**

  ```java
  return CactusWrapper.complete(modelHandle, messagesJson, null, openAiTools(toolsJson));
  ```

  `openAiTools` wraps each bare definition as
  `{"type":"function","function":{...}}`. The fix is in `CactusEngine`, the
  boundary for Cactus's habits (seam 2 of the Cactus skill), not in the
  tool code all engines share.
- **Proven to:** unit tests (`CactusEngineTest`, 3) and emulator (the torch
  turned on through Cactus; entry 42).
- **For the talk:** the most valuable thing the emulator port did. It was
  built to answer "does Cactus run?" and found that Cactus had never been
  given a tool. Two engines that accept "OpenAI-style" input still disagree
  on what that means.
