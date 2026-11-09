# Windows runtime engine research and integration decision

**Status: research only. No Windows runtime engine is integrated. The JNI/C++ host-information bridge is implemented separately; it is not a compatibility engine.**

## Host target

VYROX is an Android application. The first runtime host target is 64-bit ARM Android
(`arm64-v8a`). The app now reads Android's supported ABI list, logical CPU count,
and total memory. That probe is only a hardware profile; it does not test Vulkan
features or prove that a Windows application will run.

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
- **FEX** also runs x86 and x86-64 applications on ARM64 Linux, and its upstream
  documentation requires an x86-64 RootFS. It is an alternative to evaluate, not
  an Android drop-in library.
  Source: https://github.com/FEX-Emu/FEX
- **Winlator** demonstrates an existing Android combination of Wine with Box86/Box64
  and graphics components. It is useful as a technical reference, but VYROX will
  keep its own app, Capsule manager, storage model, and engine interfaces. Its
  source repository is LGPL-2.1; do not copy or redistribute its code without
  following that license.
  Source: https://github.com/brunodev85/winlator

## Graphics path is conditional

DXVK translates Direct3D 8/9/10/11 to Vulkan for Linux/Wine. It is not a universal
Android graphics backend. Current DXVK 3.x documentation lists Vulkan 1.4 and
specific driver features, and explicitly says first-party Android/mobile GPU driver
support is not provided. VYROX must inspect the actual device's Vulkan capabilities
and driver before enabling a DXVK path.

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
   a JNI host-information library. A Vulkan probe also reports the first available
   physical device and API/driver versions. This does not validate DXVK-required
   extensions or Windows graphics execution.
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
