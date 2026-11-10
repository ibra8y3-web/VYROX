# Third-party runtime components

This document records candidates and actual helper libraries. Runtime engines
remain separate from the parser/archiver libraries used by the Android app.

| Component | Intended role | Upstream license | VYROX status |
|---|---|---|---|
| Wine | Windows API compatibility | LGPL-2.1-or-later | Research only; no Android build is bundled |
| PRoot | Rootless Linux filesystem redirection using ptrace | GPL-2.0-or-later; verify exact patched revision | Pinned ARM64 executable and static loader build in CI and are packaged in the APK; physical-device ptrace/execve validation remains pending |
| Box64 | x86-64 Linux userspace translation on ARM64 Linux | MIT | Immutable v0.4.4 source-build action is wired for an ARM64 glibc RootFS; no physical-device build or x86-64 execution is verified |
| FEX | x86/x86-64 Linux userspace translation on ARM64 Linux | MIT | Research only; upstream targets Linux, not Android directly |
| DXVK | D3D8/9/10/11 over Vulkan | zlib | Vulkan preflight exists; no runtime is bundled |
| VKD3D-Proton | D3D12 over Vulkan | Verify exact pinned upstream license | Research only |
| Apache Commons Compress 1.28.0 | TAR archive parsing | Apache-2.0 | Used by RootFS archive staging |
| XZ for Java 1.12 | .tar.xz decompression | 0BSD | Used by RootFS archive staging |
| Linux userland | glibc libraries and runtime filesystem | Per-distribution and per-package | User-imported ARM64 RootFS images can be validated and passed to a PRoot shell probe; physical-device execution remains unverified |
| Mesa / Turnip / Zink | Open-source graphics driver options | Per-driver and per-package | No driver binaries bundled |

## Architecture boundary

1. Wine supplies Windows API compatibility.
2. PRoot supplies rootless Linux filesystem redirection. It is not a CPU
   translator, a Windows API layer, or a security boundary.
3. Box64 or another compatible translator handles x86-64 Linux userspace code.
   Box64 alone does not load a Windows PE executable.
4. The Linux userland supplies glibc and the shared libraries expected by the
   selected runtime binaries. A VYROX Capsule C:/D: tree is not a Linux RootFS.
5. DXVK and VKD3D-Proton translate Direct3D APIs over Vulkan. They do not provide
   Wine or CPU translation.

## Validation policy

Before adding a native binary, record:

- Exact upstream repository and pinned commit/tag.
- Complete license and notices for every bundled component and dependency.
- NDK/CMake or upstream build commands, ABI, API level, and reproducible output.
- Android/Bionic versus glibc assumptions and how the userland boundary is handled.
- APK size, extracted disk usage, and update mechanism.
- Physical-device test results, logs, and known limitations.

Do not download release binaries into the repository or assume a Linux desktop
build will run on Android. Do not label a component integrated merely because a
native library was built.
