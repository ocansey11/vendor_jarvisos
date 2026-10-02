# The technical ideas behind the roadblocks

Study notes for Kevin. Each section explains one idea in plain terms, says
which roadblock it explains (numbers refer to `roadblocks.md`), and ends with
a question an audience member might ask. If you can answer the question
without looking, you can present that slide.

## Contents

1. system_server, and why a system service is not an app
2. How Android is built: Soong, modules and Android.bp
3. R8 and "missing class" at the end of a build
4. Native libraries, JNI and what a .so declares
5. CPU architecture: why the emulator cannot run Cactus
6. Page size: 4K and 16K
7. Exception, Error and Throwable
8. Static initialisers and class loading
9. Boot order inside system_server
10. Storage and why system_server cannot see your files
11. Binder, Parcel and what crosses a process boundary
12. Broadcasts, exported receivers and protected broadcasts
13. SELinux in one paragraph
14. Annotation processors and generated code
15. Hard links and copies
16. Idempotent tools

---

## 1. system_server, and why a system service is not an app

Android runs most of its core services (activity manager, package manager,
window manager and about a hundred others) as threads inside one process
called `system_server`. Jarvis is one more of those. That gives it privileges
no app has, and three constraints:

- If any thread in it dies from an uncaught error, the whole process dies and
  Android restarts from the boot animation. A bug in Jarvis can take the
  phone down.
- It has no app sandbox, no app resources and no app lifecycle, so libraries
  written for apps (androidx, WorkManager) do not belong there.
- It starts very early, before many things an app takes for granted exist.

*Explains:* 3, 9, 13.
*Ask yourself:* why would a crash in Jarvis reboot the phone's UI, when a
crash in a normal app only closes that app?

## 2. How Android is built: Soong, modules and Android.bp

Android is not built as one program. It is tens of thousands of **modules**,
each described in an `Android.bp` file: its name, its source files, and the
other modules it may use. The build system (Soong, which drives a tool called
Ninja) works out the order. A source file can only import what its module
lists as a dependency.

`services.core` compiles everything under `services/core/java/`. Jarvis was
first placed there, so it was compiled as part of `services.core`, which does
not depend on ObjectBox. Giving it its own module, `services.jarvis`, with
its own dependency list, fixed that.

A "product" (`lineage_Pong`, `lineage_sdk_phone_x86_64`) is the list of
modules that go into one image for one device.

*Explains:* 2, 5.
*Ask yourself:* the same Java file compiled in one folder and failed in
another. Why?

## 3. R8 and "missing class" at the end of a build

After the Java code is compiled, a tool called R8 shrinks and optimises it
into the form Android runs (dex). To do that it needs to see every class the
code refers to. WorkManager refers to `androidx.core.R$attr`, a class of
resource IDs that is generated for apps and does not exist for a system
service. Compilation passed, because the compiler only needed the library's
signatures; R8 failed 23 minutes later, because it needed the whole graph.

*Explains:* 3.
*Ask yourself:* why did this fail at the end of the build and not at the
compile step?

## 4. Native libraries, JNI and what a .so declares

Java cannot call C++ directly. **JNI** is the bridge: a Java method marked
`native`, and a C++ function with a matching name inside a shared library
(`.so`) that Java loads with `System.loadLibrary`. Cactus (inference) and
ObjectBox (database) are both C++ libraries reached this way.

Every `.so` carries a list of other libraries it needs at load time
(`DT_NEEDED`). Android's build checks that list against what the module
declares. ObjectBox's prebuilt needed `liblog` and the module did not say so.

If the library is missing at run time, `loadLibrary` throws
`UnsatisfiedLinkError`, and calling a `native` method afterwards throws it
again.

*Explains:* 4, 5, 9.
*Ask yourself:* the library file was present and correct. What was the build
actually complaining about?

## 5. CPU architecture: why the emulator cannot run Cactus

Phones use ARM processors; the build server uses x86. Compiled C++ is
specific to one of them. The emulator is fast because it runs an x86 build of
Android directly on the server's CPU (using KVM), not by imitating ARM.

Java code does not care: it runs on either. So the Jarvis service, the
database and the tool system can all be tested on the emulator. Cactus uses
ARM-specific instructions (NEON) for speed and only builds for ARM, so
inference cannot.

This is also why the emulator build took a second two hours and a second set
of output files: every piece of native code is compiled again for x86.

*Explains:* 4, 11, 12, and the limit on every emulator result.
*Ask yourself:* what exactly has been tested on the emulator, and what is
still untested until there is a phone?

## 6. Page size: 4K and 16K

Memory is handed out in fixed-size pages. For decades that was 4 KB. Android
is moving to 16 KB pages for speed, and a native library has to be laid out
(aligned) for the page size it will run on. The emulator product checks every
library for 16K alignment. ObjectBox ships its library 4K-aligned and we
cannot rebuild it, because it is closed source. We skipped the check; that
works on a 4K kernel and would not on a 16K one.

*Explains:* 12.
*Ask yourself:* why is this a risk for any project that depends on a
closed-source native library?

## 7. Exception, Error and Throwable

Java has two families of things that can be thrown. `Exception` is for
problems code is expected to handle. `Error` is for problems the runtime
considers serious, such as a missing native library or running out of
memory. Both extend `Throwable`. `catch (Exception e)` does not catch an
`Error`.

In an app that distinction rarely matters. In `system_server` an uncaught
`Error` on a thread ends the process.

*Explains:* 9.
*Ask yourself:* why was changing one word in a `catch` the difference between
a missing feature and a phone that will not boot?

## 8. Static initialisers and class loading

A `static final` field with a computed value is set when the class is first
loaded, before any object of the class exists and before any of its methods
run. If that computation throws, the class is unusable for the life of the
process (`ExceptionInInitializerError`).

`JarvisService` computed its watch folders in a static field by calling
`Environment.getExternalStorageDirectory()`. That ran the moment
`system_server` loaded the class. No `try`/`catch` inside the service could
have helped, because none of the service's code had started.

*Explains:* 13.
*Ask yourself:* the service has error handling in its start-up code. Why did
it not catch this?

## 9. Boot order inside system_server

`system_server` starts its services one after another in a fixed order.
Jarvis is started in the "other services" phase. The storage service is not
up at that point, so asking for storage volumes returned nothing. Services
that need something later are told when it is ready through boot phases
(`onBootPhase`), which is where work that depends on other services belongs.

The code in `SystemServer` that starts Jarvis is wrapped in its own
`try`/`catch`, as it is for most optional services. That is why Android still
booted: it logged "BOOT FAILURE starting Jarvis Service" and carried on
without it. (Core services such as the activity manager are not wrapped; if
one of those fails, the device does not boot.)

*Explains:* 13.
*Ask yourself:* why did the phone boot normally even though a system service
failed to start?

## 10. Storage and why system_server cannot see your files

`/storage/emulated/0` (what apps call "internal storage") is not a plain
folder. It is presented to each app through a filter that enforces
permissions, and each process gets its own view of it. `system_server`
deliberately has no view of it: Android's security rules keep the most
privileged process away from files any app can write.

So a file watcher inside the service sees an empty path. The Android way to
do this is from an app-level component (which does have a view of storage)
that hands work to the service.

*Explains:* 14.
*Ask yourself:* why is the most privileged process on the phone the one that
cannot read your Documents folder?

## 11. Binder, Parcel and what crosses a process boundary

Apps and `system_server` are separate processes and cannot share objects.
They talk through **Binder**. Anything sent is flattened into a **Parcel** on
one side and rebuilt on the other.

An object that is `Parcelable` is written with its class name, so the other
side knows what to rebuild. Jarvis sent a `ResultReceiver` (a reply channel)
that was an anonymous subclass named `ToolDispatcher$1`. The app tried to
rebuild a `ToolDispatcher$1`, a class that exists only inside
`system_server`, and crashed. The fix flattens it and rebuilds it as a plain
`ResultReceiver` before sending; the plain one still delivers replies to the
original.

*Explains:* 15.
*Ask yourself:* the reply object worked perfectly inside the service. Why did
it break only when an app received it?

## 12. Broadcasts, exported receivers and protected broadcasts

A broadcast is a message identified by an action string, here
`com.jarvisos.TOOL`. An app receives it with a receiver declared in its
manifest. For Jarvis to reach an app's receiver, the receiver must be
**exported** (open to other processes). By default that means open to every
process, so any app could have sent `com.jarvisos.TOOL` to another app and
triggered its tools.

A **protected broadcast** is an action string the system declares as its own.
Only system processes may send it; anyone else gets a `SecurityException`.
One line in the framework's manifest closed the hole.

*Explains:* 16, 17.
*Ask yourself:* how does an app know that a tool call really came from
Jarvis?

## 13. SELinux in one paragraph

On top of normal file permissions, Android labels every process and every
file, and a policy says which labels may do what to which. A denial shows up
in the log as `avc: denied`. Jarvis's two services needed labels of their own
to be registered at all. On the emulator there were no denials involving
Jarvis: the database lives under `/data/system`, which `system_server` is
already allowed to use.

*Explains:* the "no SELinux denials" line in the emulator results.
*Ask yourself:* what would you look for in the log to know SELinux had
blocked something?

## 14. Annotation processors and generated code

Some libraries write part of your program for you at build time. You mark a
class with an annotation (`@Entity`), and an **annotation processor** reads
it and generates the supporting classes. ObjectBox generates, for each
entity, a `_` class describing its fields and a `Cursor` class that reads and
writes it, plus `MyObjectBox`, which holds the schema.

In an app, ObjectBox's Gradle plugin runs this for you. Android's platform
build has no such plugin, so the generated classes had been written by hand
as placeholders. They compiled, because their shape was right, and threw at
run time, because the part that does the work was missing.

*Explains:* 7, 8.
*Ask yourself:* how can code compile, build into a ROM, and still be certain
to fail the first time it runs?

## 15. Hard links and copies

A file name is a pointer to data on disk. A **copy** makes new data with its
own name. A **hard link** makes a second name for the same data. Change the
data through either name and both "files" change, because there is only one.
The vanilla ROM backup was a hard link to the build's output file; the next
build rewrote that data in place.

*Explains:* 6.
*Ask yourself:* how would you check whether two files are really one?
(`ls -li` shows the same inode number, and a link count above 1.)

## 16. Idempotent tools

An operation is **idempotent** if doing it twice has the same effect as doing
it once. `set_alarm(07:00)` in the sample app checks its own alarm list
first; a repeat returns "already exists. Nothing was changed."

This matters more for an agent than for a person. A model may repeat a call,
retry after a timeout, or lose track of what it has done. Putting the check
in the tool, against the app's real state, is more reliable than hoping the
model remembers. The second half is a **read tool** (`list_alarms`) so the
model can look before acting, and a clear **error message** so it can correct
a bad call.

*Explains:* 15, 18, and the design of `apps/JarvisAlarmDemo`.
*Ask yourself:* why not have Jarvis keep its own list of alarms it has set?
