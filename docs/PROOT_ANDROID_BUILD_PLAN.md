# PRoot Android integration plan

## Role

PRoot is a user-space implementation of Linux root filesystem and bind-mount
redirection. It relies on `ptrace`; it does not translate CPU instructions or
implement Windows APIs.

Upstream manual: https://github.com/proot-me/proot/blob/master/doc/proot/manual.txt
Android reference fork: https://github.com/termux/proot
Termux package build files: https://github.com/termux/termux-packages/tree/master/packages/proot

## Current implementation

`ProotCommandPlanner` builds a ProcessBuilder-ready argument vector and checks:

- the PRoot binary is executable, stored below an approved app-private root,
  and verified as a 64-bit AArch64 ELF before a command plan can be created;
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

It does not start PRoot. The APK currently contains no PRoot binary.

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

1. Build the pinned sources for `arm64-v8a` with the Android NDK/Bionic
   toolchain, including the exact Termux patches and dependency versions.
2. Build it for `arm64-v8a` with the Android NDK/Bionic toolchain, including all
   required Termux patches and dependencies.
3. Audit GPL-2.0-or-later obligations and preserve notices and corresponding
   source availability for the exact packaged build.
4. Package it in an executable location compatible with Android's linker and
   app storage execution policy.
5. Test PRoot on physical Android 12+ devices: `ptrace` permission, SELinux
   behavior, bind mounts, signal handling, process exit, and storage paths.
6. Run a harmless ARM64 Linux command such as `/bin/echo` inside an imported
   RootFS and capture stdout, stderr, exit code, and exact device/build details.
7. Only after that gate passes, add Wine ARM64 to the RootFS and test
   `wineboot` / `wine --version`; this still does not establish Windows x64
   application compatibility.

## Risks

- Android app sandbox and SELinux policy can block ptrace even though Linux
  generally exposes ptrace to unprivileged processes.
- A PRoot command plan is not process isolation or a hardened security sandbox.
- Imported RootFS images are executable content; importing one is not a malware
  scan or a trust guarantee.
- The PRoot binary must be built for Android/Bionic, not copied from desktop Linux.
