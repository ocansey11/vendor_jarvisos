# Needle engine prebuilts

The Cactus Compute engine that runs Needle 3 (tool calling, 35 MB) and Whistle
(speech-to-text, 17 MB) from `.cact` files. Downloaded 8 Oct 2026 from
https://huggingface.co/Cactus-Compute/needle3 (Apache 2.0, see LICENSE).
Only binaries are published; there is no engine source.

| File | sha256 |
|------|--------|
| android-arm64/libneedle.a | 4522a16eebaaf569ebacd0ac682712a8d91acc88a2a50a44fd65f332c8ebfdbb |
| linux-x86_64/libneedle.a | 8556785e3f3562bb6e402d48770f34c0797f23b9571c07457cdc577928b0b9d3 |
| needle.h (same for both) | 914bbd00423939b02fc349f91cdb7c12531b3f5bb7105b743e0e93c7e409d7b1 |

- `android-arm64` is the one for the phone. It is built against the NDK's
  libc++ (`std::__ndk1`). `android-arm64/libc++_static.a` and `libc++abi.a`
  are that library, copied from the build tree's own toolchain
  (`prebuilts/clang/host/linux-x86/clang-r536225/android_libc++/ndk/aarch64/lib`,
  Apache 2.0 with LLVM exceptions); the JNI library links them.
- There is no android-x86_64 build. The emulator uses `linux-x86_64`, which is
  a glibc build; `native/needle/glibc_shim.cpp` supplies the few glibc-only
  symbols it needs. That is for testing on the emulator only.
- Neither library imports any socket or resolver function (checked with
  `llvm-nm -u`), which matches the claim that inference stays on the device.

The models are not in this repo. Install them on a device with
`cmd jarvis install-model needle < needle3.cact` and
`cmd jarvis install-model whistle < whistle.cact`.
