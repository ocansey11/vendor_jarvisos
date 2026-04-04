# JarvisOS Handoff — Session 7 (Mar 18 2026)

## READ THIS FIRST

The build environment has a fundamental mismatch that needs fixing at uni:
- Base LineageOS source: `lineage-21.0` (Android 14)
- Target device (Nothing Phone 2 / Pong): requires `lineage-22.2` minimum
- Decision: re-sync base on `lineage-22.2` at university on fast connection

---

## Step 1 — Re-sync at uni (do this first, on fast connection)

```bash
cd ~/android/lineage
repo init -u https://github.com/LineageOS/android.git -b lineage-22.2 --git-lfs
repo sync -c -j4 --no-clone-bundle 2>&1 | tee ~/sync.log
```

Run inside tmux (`tmux attach -t jarvis`) so it survives if connection drops.
Takes ~2-3 hours on a fast connection. ~150GB.

## Step 2 — After sync, update pong.xml

```bash
cat > ~/android/lineage/.repo/local_manifests/pong.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <project name="LineageOS/android_device_nothing_Pong"
           path="device/nothing/Pong"
           remote="github"
           revision="lineage-22.2" />
</manifest>
EOF

repo sync device/nothing/Pong -c --no-clone-bundle -j4
```

## Step 3 — Rebase frameworks/base onto lineage-22.2

Your JarvisOS code needs to sit on top of the new base:

```bash
cd ~/android/lineage/frameworks/base
git fetch lineage
git rebase lineage/lineage-22.2
```

Expect some conflicts — resolve the same way as before (cat > file << EOF pattern).
Then push:
```bash
git push origin lineage-21.0 --force
```

## Step 4 — Lunch and build

```bash
cd ~/android/lineage
source build/envsetup.sh
breakfast Pong
export SOONG_ALLOW_MISSING_DEPENDENCIES=true
m libcactus 2>&1 | tee ~/cactus_build.log
```

---

## Code state — ALL SAFE AND PUSHED

| Repo | Branch | Remote | Status |
|------|--------|--------|--------|
| frameworks/base | lineage-21.0 | origin/lineage-21.0 | ✅ clean |
| vendor/jarvisos | main | JarvisOs/main | ✅ clean |
| vendor/cactus | main | JarvisOs/main | ✅ clean |

---

## What was done this session (Session 7)

### Code
- `ModelRegistry.java` — new file. Singleton map of name → (modelHandle, indexHandle).
  Replaces static handles in RagIndexWorker. Adding a new model is one register() call.
- `RagIndexWorker.java` — removed static handle fields + ensureHandles(). Now calls
  `ModelRegistry.getInstance().getReady("rag")` per task.
- `RagService.java` — Step 2 now registers "rag" + "tools" via ModelRegistry. Passes
  tools handles to ToolScannerService. onDestroy() added — properly tears down everything.
- `vendor/jarvisos/prebuilts/objectbox/Android.bp` — fixed `static_libs` → jars array
  (java_import doesn't support static_libs).

### Git housekeeping
- Renamed frameworks/base branch `lineage-23.0` → `lineage-21.0` (was just a name, not a version)
- Force pushed to origin/lineage-21.0, deleted lineage-23.0 from remote
- Updated jarvos.xml to point to lineage-21.0
- Resolved vendor/jarvisos diverged branch via git rebase

### Build attempts
- Tried Pong on lineage-21.0 — Pong only exists on lineage-22.2+, failed
- Tried Pixel 6 (raviole) on lineage-21.0 — synced raviole + gs101 successfully
- breakfast lineage_oriole pulled more dependencies (raviole-kernel, gs-common)
- Confirmed build system works — Soong bootstraps correctly with a proper device tree
- Decision: stop fighting lineage-21.0, do proper re-sync at uni on lineage-22.2

### Manifest state
- `.repo/local_manifests/jarvos.xml` — correct, points to lineage-21.0
- `.repo/local_manifests/pong.xml` — empty placeholder, will be updated after sync
- `.repo/local_manifests/pixel6.xml` — leftover from failed attempt, safe to delete

### Device trees on disk (will survive the re-sync)
- `device/google/raviole/` — Pixel 6 tree (lineage-21)
- `device/google/gs101/` — Tensor chip common (lineage-21)
- `device/nothing/Pong/` — Nothing Phone 2 (lineage-21 from Nothing-phone-2-Development fork)

---

## Key architectural facts

- ObjectBox owns metadata. Cactus owns vectors.
- `DocumentChunk.cactusIndexId` is the bridge into Cactus binary index
- Two-stage retrieval: ObjectBox narrows (free) → Cactus re-ranks (bounded)
- ModelRegistry: "rag" model for documents, "tools" model for tool embeddings
- Same model file for both — separate index directories — indexes must never be mixed
- system_server process — memory leaks crash the whole phone
- No Kotlin in system_server

## Package map
```
android.app.rag          — public API (RagManager, IRagService)
com.android.server.rag   — system service implementation (RagService, all workers)
```
