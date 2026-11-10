# Kevin's account skills against the code (10 Nov 2026)

Three account skills were written on 2 Oct 2026, before most of the build.
They live in Kevin's Claude account, so only his Claude sees them, and they
can only be edited there. This file lists what each gets wrong now, so Kevin
can update or retire them. The decisions themselves are kept up to date in
`decisions.md`.

## cactus-integration

| The skill says | The code now |
|---|---|
| `CactusWrapper.java` is the boundary (seam 1) | `inference/Inference` is the boundary, with two engines behind it. `CactusWrapper` is Cactus's JNI only |
| The fork predates v2.0 | `vendor/cactus` is on branch `jarvis-v2.2.2` |
| arm64-v8a only; the emulator cannot run it | Builds for x86_64 through a SIMDe shim (22 Oct); `auto` does not pick it there |
| Build with `cactus build --android` into `jniLibs` | Built into the ROM by Soong (`vendor/cactus/jarvisos/`) |
| Decision: indirect the index IDs (2 Oct) | **Not done.** Entities still store engine IDs (`cactusIndexId`). Per-engine index folders reduce the damage, they do not solve it |
| Tool calls: consume `function_calls` behind a seam | Done through `inference/Completion`; Cactus needs tools wrapped as `{"type":"function",...}` (roadblock 50) |
| Needle is for the demo; Gemma 4 is the architecture | Needle 3 is in the request path (fast path). The emulator's chat model is Qwen3 1.7B on llama.cpp. The skill's own warning ("the demo model quietly becoming the architecture") now applies |
| Disable cloud handoff, libcurl, `cactus serve` | Telemetry replaced by a no-network stub (`telemetry_stub.cpp`); the other two are not used |
| The decision rule for staying on Cactus: diff the fork | Overtaken: llama.cpp was added as the second engine (5 Oct) |

Still true: the licence and revenue ceiling, the 3-bit floor for tool
calling, the privacy items. Re-check version numbers; they are from 2 Oct.

## jarvisos-storage-layer

| The skill says | The code now |
|---|---|
| The two-week rule: phone test by 16 Oct | The date has passed; there is still no phone test. Decide whether the rule still governs |
| Vectors live in `cactus_index_t` | Only on the Cactus engine. llama.cpp uses `inference/FlatVectorIndex` (its own file) |
| ObjectBox never stores embeddings | **True**, still the rule |
| Keep ObjectBox behind a storage layer | **Not done.** About 58 direct `JarvisStore.box(...)`/`Box<>` uses outside `core/` and `model/` |
| Indirect the index IDs | **Not done** (as above) |
| (not mentioned) | New entities since: `AgentSession`, `AgentTurn` (agent loop), conversations kept 24 h by `History` |

Still true: the licence findings and the escape route (Room and
sqlite-vec). Re-check the ObjectBox licence pages; they are from 2 Oct.

## lineageos-pong-build

| The skill says | The code now |
|---|---|
| Tree at `~/android/lineage` | `/srv/jarvis/lineage` (moved 28 Oct) |
| `lunch lineage_Pong-userdebug`, then `mka bacon` | `breakfast`, never `lunch` (roadblock: `lunch` caused a full rebuild; the guard hook refuses it). Build with `jh build rom` |
| Build as yourself in tmux | Builds run as `jarvisbuild` through `jh`; building as anyone else fails, root included |
| ccache at `~/.cache/ccache`, 40 GB | `/srv/jarvis/ccache`, 20 GB, shared |
| Keep disk below 80 % | It is at 92 %; more storage has been asked about |

Still true: device facts (Pong, sm8475, lineage-22.2), vendor blobs from
TheMuppets, `-userdebug`, flashing order. The first Pong build since the
tree moved may hit old `/root/android/lineage` paths in the build records
once.

## Suggested next step

Move these three into the repo (`vendor/jarvisos/skills/`) in corrected form
and remove the account copies, so there is one version that the team also
gets. Kevin decides.
