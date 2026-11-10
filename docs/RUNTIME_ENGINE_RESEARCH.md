# Windows runtime engine research and integration status

## Current implementation status

VYROX now contains source paths for a multi-layer Windows compatibility experiment inside an Android-hosted ARM64 Linux RootFS:

- Android PRoot native build and package flow.
- Box64 v0.4.4 guest build plan with an x86-64 syscall smoke check.
- WineHQ stable amd64 package installation through Box64.
- Real per-Capsule prefix initialization using `wineboot -u`.
- EXE/MSI process launch, Windows desktop framebuffer, touch/keyboard forwarding and PCM audio bridge.
- Program discovery and Windows executable icon extraction.
- Pinned DXVK 3.1.1 and VKD3D-Proton 3.1 installation plans with a guest Vulkan preflight and DLL checks.

These are **source implementation milestones, not end-to-end physical-device verification**. The JNI/C++ host-information bridge is not a Windows compatibility engine. CI verifies source compilation/tests/packaging; only a real Android phone can prove ptrace, Box64, Wine, Vulkan, screen, input and audio behavior together.

## Host target and separation of layers

The first target is 64-bit ARM Android (`arm64-v8a`) with an imported ARM64 Debian/Ubuntu glibc RootFS.

- **PRoot** redirects Linux filesystem/process paths inside the imported RootFS. It does not provide Windows APIs or a security boundary.
- **Box64** translates x86-64 Linux ELF programs on ARM64 Linux. It is not a Windows PE loader.
- **Wine** provides Windows API compatibility and needs a compatible x86-64 Linux userland plus a working Wine prefix.
- **DXVK** translates selected Direct3D 9/10/11 interfaces over Vulkan.
- **VKD3D-Proton** translates Direct3D 12 over Vulkan.
- **Android UI bridges** are responsible for the guest display, pointer/keyboard events and PCM audio transfer.

This is a Linux guest/runtime stack hosted by an Android app; it is not an independent Windows kernel or a VM.

## Graphics and Vulkan boundary

The app's Android Vulkan capability probe describes the host device. The optional graphics installer separately probes the Vulkan environment visible inside the Linux RootFS, downloads pinned upstream releases, runs their Wine setup scripts through Box64 and verifies the expected DLLs in the selected Capsule prefix.

A guest-side Vulkan device can be software-backed (for example, lavapipe). That does not imply Android GPU acceleration. Android/mobile driver compatibility, Vulkan extensions, descriptor features, push constants, required WSI behavior and actual D3D rendering must all be tested on the specific device. Presence of the translation DLLs is an installation check, not a successful game test.

## Source references

- [Box64](https://github.com/ptitSeb/box64)
- [Wine](https://www.winehq.org/)
- [DXVK](https://github.com/doitsujin/dxvk)
- [VKD3D-Proton](https://github.com/HansKristian-Work/vkd3d-proton)
- [PRoot](https://github.com/termux/proot)

For the execution flow, package checks and readiness matrix, see [Windows runtime and Prefix architecture](WINDOWS_PREFIX_ARCHITECTURE.md). For licenses and redistribution obligations, see [third-party components](THIRD_PARTY_COMPONENTS.md).

## Physical-device gates

Before calling VYROX a working Windows environment, test and archive:

1. PRoot starts the imported ARM64 RootFS shell on a physical phone.
2. Box64 builds and runs a normal x86-64 Linux workload, not only a minimal syscall smoke.
3. Wine's version command and real `wineboot -u` succeed through Box64.
4. EXE and MSI test installers complete and the resulting applications are detected.
5. The guest desktop is visible and can be controlled through touch/keyboard.
6. Audio reaches Android with acceptable latency and stops cleanly with the session.
7. DXVK/VKD3D render real Direct3D 9/11/12 test workloads using the target phone's actual Vulkan path.

Do not mark a gate complete because a package downloaded, a process was launched, or a DLL file exists. Record the phone model, Android version, ABI, RootFS distribution, exact component revisions, logs, test application and result.
