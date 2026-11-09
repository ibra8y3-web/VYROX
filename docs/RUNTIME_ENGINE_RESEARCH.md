# Windows runtime engine research and integration decision

**Status: research only. No Windows runtime engine is integrated. The JNI/C++ host-information bridge is implemented separately; it is not a compatibility engine.**

## Host target

VYROX is an Android application. The first runtime host target is 64-bit ARM Android
(`arm64-v8a`). The app reads Android's supported ABI list, logical CPU count, and total memory.
Its Vulkan probe is being extended to report actual device extension names,
push-constant size, and descriptor-indexing support. These are preflight checks
only; they do not prove DXVK or Windows applications will run on a particular
Android device.

## Separate the Windows API layer from CPU translation

- **Wine** provides the Windows API compatibility layer. It is not a CPU translator.
  Wine needs a compatible Unix-like host environment and a correctly built runtime
  prefix. Its Android integration must be built and tested specifically for Android;
  merely placing a Wine executable in the APK is not sufficient.
- **Box64** targets x86-64 Linux user-space programs on 64-bit ARM Linux and forwards
  calls to host Linux libraries. It is not a standalone loader for Windows PE/EXE
  files. A VYROX route using Box64 therefore needs a compatible Linux user-space
  environment and Wine, not just an EXE file picker.
  Source: https://github.com/ptitSeb/box64
- **FEX** runs x86 and x86-64 applications on ARM64 Linux and requires an x86-64
  RootFS. Its upstream FAQ says Android is not a supported FEX target; FEXCore
  can be built as a library for Android, but that alone is not a usable emulator.
  Source: https://github.com/FEX-Emu/FEX
- **Winlator** demonstrates an existing Android combination of Wine with Box86/Box64
  and graphics components. It is useful as a technical reference, but VYROX will
  keep its own app, Capsule manager, storage model, and engine interfaces. Its
  source repository is LGPL-2.1; do not copy or redistribute its code without
  following that license.
  Source: https://github.com/brunodev85/winlator

## Graphics path is conditional

DXVK translates Direct3D 8/9/10/11 to Vulkan for Linux/Wine. DXVK 2.7+ documents
a Vulkan 1.3 baseline and required extensions including
`VK_EXT_depth_clip_enable`, `VK_KHR_maintenance5`,
`VK_KHR_maintenance6`, `VK_EXT_robustness2`, and
`VK_EXT_transform_feedback`, plus descriptor indexing and at least 256 bytes of
push constants. Its driver documentation does not provide first-party support for
Android/mobile GPU drivers. VYROX must probe and then physically test each driver.

Source: https://github.com/doitsujin/dxvk

## Licensing notes for evaluation

- Box64: MIT.
- FEX: MIT.
- DXVK: zlib/libpng.
- Winlator app source: LGPL-2.1.

These licenses still require per-component review, notices, and any applicable
source-offer or redistribution obligations before shipping binaries. The upstream
license of each exact Wine build and any graphics driver package must also be
checked at the pinned revision.

## Implementation gates

1. **Host profile:** implemented in the Android app; unit tests verify the ARM64
   ABI decision.
2. **Native build infrastructure:** implemented with a pinned NDK/CMake build and
   a JNI host-information library. The Vulkan probe reports the physical device
   and is being extended to expose the required DXVK extension and feature values.
3. **Runtime planning:** implemented in a separate Kotlin/JVM module. It records
   upstream component roles and licenses and reports missing Windows x64 runtime
   prerequisites; it does not start a process or install third-party binaries.
3. **Linux user-space and Wine:** select an Android-compatible build strategy,
   pin upstream revisions, document how glibc/Bionic boundaries are handled, and
   build an ARM64 Wine runtime.
4. **x86-64 execution:** select one translator path (Box64 or FEX) only after proving
   it can run with the chosen Android user-space and Wine build.
5. **Graphics:** add Vulkan/DXVK only after testing the target GPU and driver.
6. **Execution proof:** launch a small redistributable Windows test program on a
   physical ARM64 phone and capture the exact device, ABI, engine revisions,
   logs, and exit status.

Do not mark a gate complete because dependencies downloaded or a native library
compiled. Windows execution requires the physical-device proof in gate 6.
