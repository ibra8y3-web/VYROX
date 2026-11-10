# Third-party runtime components

This inventory separates Android application libraries from the runtime components used inside an imported Linux RootFS. The exact source revision and license must remain recorded before redistributing binaries.

| Component | Intended role | Upstream license | VYROX source status |
|---|---|---|---|
| PRoot (Termux-derived build) | Rootless Linux filesystem/process redirection using ptrace | GPL-2.0-or-later; confirm notices for the pinned patched revision | Pinned Android native build, packaged loader and PRoot command planner; device ptrace tests pending |
| Box64 v0.4.4 | x86-64 Linux userspace translation on ARM64 Linux | MIT | Immutable source build action, ELF/version checks and minimal syscall smoke path inside ARM64 RootFS; device workload pending |
| WineHQ stable (11+) | Windows API compatibility | LGPL-2.1-or-later | Guest package-install plan and per-Capsule Wineboot path implemented; physical device execution pending |
| DXVK 3.1.1 | Direct3D 9/10/11 translation over Vulkan | zlib/libpng | Official release install plan and DLL verification; device D3D rendering pending |
| VKD3D-Proton 3.1 | Direct3D 12 translation over Vulkan | LGPL-2.1-or-later | Official release install plan and DLL verification; device D3D12 rendering pending |
| FEX | Alternative x86/x86-64 Linux userspace translation | MIT | Research only; not integrated |
| Mesa / Turnip / Zink | Vulkan/OpenGL driver stack | Per project/package | Not bundled; guest-side Vulkan probe only |
| Apache Commons Compress 1.28.0 | TAR archive parsing | Apache-2.0 | Used by RootFS archive staging |
| XZ for Java 1.12 | .tar.xz decompression | 0BSD | Used by RootFS archive staging |

## Architecture boundaries

1. Wine supplies Windows API compatibility; it is not an x86-64 CPU translator.
2. Box64 translates Linux x86-64 ELF programs and does not load Windows PE executables by itself.
3. PRoot redirects Linux filesystem/process paths. It is not a Windows API layer or hardened security sandbox.
4. The imported Linux RootFS supplies glibc and the shared libraries required by its guest executables. A Capsule's C:/D: folders are mounted data directories, not a Linux RootFS.
5. DXVK and VKD3D-Proton translate selected Direct3D APIs over Vulkan. They do not provide Wine, Box64 or a GPU driver.

## Reproducibility and redistribution checklist

Before shipping runtime binaries:

- Record the exact upstream repository, tag/commit and release archive.
- Include the exact upstream license and required notices for each component.
- Check the ABI and glibc/Bionic assumptions of every binary.
- Document the source/build commands and reproducible build evidence.
- Record APK size, RootFS disk usage and the runtime update mechanism.
- Capture physical-device test results and known limitations.

A dependency download, APK build, or DLL-presence check alone is not proof that Windows applications run on Android.
