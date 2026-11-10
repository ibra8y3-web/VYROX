# Windows runtime engine research and integration decision

**Status: Windows compatibility is not integrated. The source now includes a pinned Box64 v0.4.4 build action for an ARM64 glibc RootFS, but Box64 has not been built or executed on a physical Android device. The JNI/C++ host-information bridge is not a compatibility engine.**

## Host target

VYROX is an Android application. The first runtime host target is 64-bit ARM Android
(`arm64-v8a`). The app reads Android's supported ABI list, logical CPU count, and total memory.
Its Vulkan probe reports actual device extension names, push-constant size,
descriptor indexing, scalar block layout, and integer feature support. These are preflight checks
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

DXVK translates Direct3D 8/9/10/11 to Vulkan for Linux/Wine. The current DXVK 3.x
driver guide requires Vulkan 1.4 and these device extensions:
`VK_EXT_depth_clip_enable`, `VK_KHR_maintenance5`,
`VK_KHR_maintenance6`, `VK_KHR_load_store_op_none`,
`VK_EXT_robustness2`, and `VK_EXT_transform_feedback`. Drivers must also
expose at least 256 bytes of push constants, descriptor indexing, 8-bit, 16-bit,
and 64-bit integer types, and `scalarBlockLayout`. Upstream does not provide
first-party support for Android/mobile GPU drivers. VYROX must probe and then
physically test each driver.

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


## Box64 build action\n\nThe app offers a pinned Box64 v0.4.4 source build inside an imported ARM64 Debian/Ubuntu glibc RootFS. It uses PRoot guest uid/gid mapping (`-0`) for package installation; that does not grant Android root. For network-based APT/Git operations it binds `/dev/null`, `/dev/zero`, `/dev/random`, `/dev/urandom`, `/proc`, and a temporary `/etc/resolv.conf` generated from Android's active network DNS servers. The resolver file is app-private, marked read-only, and deleted after the build process exits. These binds are restricted to the Box64 build request, not enabled for ordinary Capsule processes.\n\nThe build script verifies the exact upstream commit, compiles with the ARM64 DynaRec and `BAD_SIGNAL` flags, checks `box64 --version`, then writes metadata. It does not test an x86-64 program, run Wine, or launch an EXE.\n\n## PRoot boundary

PRoot is the missing userland launcher between an imported Linux RootFS and Wine.
It relies on `ptrace` and implements rootfs redirection/bind mounts in user space.
The current code includes a command planner that validates the RootFS path,
guest executable, bind source roots, and argument vector. It deliberately does
not start a process until an Android-compatible PRoot executable is built,
licensed, packaged, and verified on a real device.

The Termux PRoot fork and package build process are the most useful Android
references. VYROX must use a pinned Termux-derived source revision and review
its patches, dependencies, and GPL notices. A generic desktop Linux PRoot binary
is not assumed to run on Android.
