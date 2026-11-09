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

- the PRoot binary is executable and stored below an approved app-private root;
- the RootFS is an approved, metadata-validated ARM64 image;
- the guest command exists and is executable inside that RootFS;
- the guest working directory exists;
- bind sources are inside explicitly approved host roots;
- guest paths are absolute and contain no traversal;
- arguments are passed as separate vector elements rather than shell-concatenated.

It does not start PRoot. The APK currently contains no PRoot binary.

A separate `RuntimeProcessSupervisor` can start a validated command plan,
capture combined stdout/stderr into a bounded private log, enforce a maximum
runtime, and terminate the process. It is generic process supervision, not a
security sandbox, and the VYROX UI does not yet invoke it for Windows programs.

## Required next steps

1. Pin a specific Termux PRoot source revision and capture its full patch set.
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
