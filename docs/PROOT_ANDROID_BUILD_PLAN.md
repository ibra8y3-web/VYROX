# PRoot Android integration plan

## Role

PRoot is a user-space implementation of Linux root filesystem and bind-mount
redirection. It relies on `ptrace`; it does not translate CPU instructions or
implement Windows APIs.

Upstream manual: https://github.com/proot-me/proot/blob/master/doc/proot/manual.txt
Android reference fork: https://github.com/termux/proot
Termux package build files: https://github.com/termux/termux-packages/tree/master/packages/proot

The initial Android source candidate is pinned in
`third_party/proot-android.lock.json`:

- Termux PRoot `v5.1.107.102`, commit
  `52d65a4dc140ca554d241a1438ddd4f6b00c9813`, Termux package label GPL-2.0.
- `libandroid-shmem` `0.7`, BSD-3-Clause.
- `libtalloc` `2.5.0`: its shared library source is identified as
  LGPL-3.0-or-later, while the Termux package recipe labels the package GPL-3.0.
  Preserve both declarations pending review of the exact distributed files
  and notices.

The Termux package recipe declares PRoot dependencies on
`libandroid-shmem` and `libtalloc`, enables the Android shared-memory
compatibility option, and builds against Bionic. Source pins are not proof of a
successful VYROX build; all three sources still require reproducible Android
build recipes and a license/notice review.

## Current implementation

`ProotCommandPlanner` builds a ProcessBuilder-ready argument vector and checks:

- the PRoot binary is a 64-bit AArch64 ELF packaged under the APK's
  `applicationInfo.nativeLibraryDir`; it is not launched from writable
  `filesDir` storage;
- the RootFS is an approved image with VYROX metadata, a matching architecture tag, a valid archive digest, and a 64-bit little-endian AArch64 shell;
- the guest command exists and is executable inside that RootFS;
- the guest working directory exists;
- bind sources are inside explicitly approved host roots;
- guest paths are absolute and contain no traversal;
- arguments are passed as separate vector elements rather than shell-concatenated;
- only a small guest environment allowlist is accepted; host library-injection
  variables such as `LD_PRELOAD` and `LD_LIBRARY_PATH` are rejected;
- the command plan constructor is internal to the runtime module, so application
  code obtains plans from the validator rather than assembling arbitrary vectors.

It does not start PRoot. The APK currently contains no PRoot binary. Android 10/API 29 and later reject
`execve()` of binaries from a target app's writable home directory. The
packaging path must therefore place a PRoot PIE executable inside the APK's
native-library payload and resolve it through `applicationInfo.nativeLibraryDir`.
The Android Gradle build now uses legacy JNI packaging so native payloads are
extracted into `nativeLibraryDir`. The app-side locator checks for
`libproot.so`, an executable ARM64 little-endian ELF file, without copying it
to writable storage. This packaging strategy still needs an actual APK
extraction/execute test on a physical Android 12+ device; a matching file path
alone does not prove that a particular executable layout will run.

A separate `RuntimeProcessSupervisor` can start a validated command plan,
capture combined stdout/stderr into a private log whose full size is bounded,
enforce a maximum runtime, and terminate the process. It accepts only
executables, working directories, and log paths below caller-approved roots;
it validates argument/environment counts and lengths before creating a log.
It is generic process supervision, not a security sandbox, and the VYROX UI
does not yet invoke it for Windows programs.

## Pinned Android source set

The reference source set is now recorded in
`third_party/proot-android.lock.json` and checked by
`scripts/check_proot_manifest.py`:

- Termux PRoot `v5.1.107.102`, source commit
  `52d65a4dc140ca554d241a1438ddd4f6b00c9813`, archive SHA-256
  `30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852`,
  GPL-2.0.
- `libtalloc` `2.5.0`, SHA-256
  `912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007`.
  The Termux recipe labels its package GPL-3.0, while the talloc library source
  headers identify LGPL-3.0-or-later. The exact source and notices need a final
  review before redistribution.
- `libandroid-shmem` `0.7`, source commit
  `7f0bd7e25dbdd146265aff7c6a890029e374622d`, SHA-256
  `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867`,
  BSD-3-Clause.

These are source pins only. No PRoot binary is included in the APK. The Termux
build recipe points its unbundled loader at its own package prefix; VYROX must
not reuse that absolute path. The first VYROX build should retain the embedded
loader or implement a relocatable loader directory and test it.

## Required next steps

1. Reproduce the pinned Termux PRoot build recipe, including the exact Termux
   patches and both dependencies, with the Android NDK/Bionic toolchain.
2. Review the exact source files, package labels, notices, and distribution
   obligations for PRoot, libtalloc, and libandroid-shmem.
3. Produce an ARM64 PIE executable and its required shared libraries. Package
   them under `app/src/main/jniLibs/arm64-v8a/` (the executable filename is
   `libproot.so`) and keep `useLegacyPackaging` enabled so the payload is
   extracted into `applicationInfo.nativeLibraryDir`.
4. Verify the extracted file is a 64-bit little-endian AArch64 PIE executable,
   its dynamic dependencies resolve from approved packaged/system paths, and
   it cannot be replaced by app code after installation.
5. Test `execve()` and PRoot startup on physical Android 12+ devices. Capture
   device model, Android build, target SDK, ABI, exit status, and logs.
6. Test PRoot `ptrace`, SELinux behavior, bind mounts, signals, process exit,
   and storage paths on physical devices.
7. Run a harmless ARM64 Linux command such as `/bin/echo` inside an imported
   RootFS and capture stdout, stderr, exit code, and exact device/build details.
8. Only after that gate passes, add Wine ARM64 to the RootFS and test
   `wineboot` / `wine --version`; this still does not establish Windows x64
   application compatibility.

## Risks

- Android app sandbox and SELinux policy can block ptrace even though Linux
  generally exposes ptrace to unprivileged processes.
- A PRoot command plan is not process isolation or a hardened security sandbox.
- Imported RootFS images are executable content; importing one is not a malware
  scan or a trust guarantee.
- The PRoot binary must be built for Android/Bionic, not copied from desktop Linux.
