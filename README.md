<p align="center">
  <img src="docs/images/vyrox-hero.svg" alt="VYROX desktop runtime for Android" width="100%">
</p>

<p align="center">
  <a href="https://github.com/ibra8y3-web/VYROX/actions/workflows/android.yml"><img src="https://github.com/ibra8y3-web/VYROX/actions/workflows/android.yml/badge.svg" alt="Android CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-72d8c7.svg" alt="Apache 2.0"></a>
  <img src="https://img.shields.io/badge/platform-Android-91a9ff.svg" alt="Android">
  <img src="https://img.shields.io/badge/host-ARM64%20first-91a9ff.svg" alt="ARM64 first">
</p>

<p align="center"><strong>Your PC. Your Phone. Your Environment.</strong></p>

# VYROX

VYROX is an open-source Android application project that builds a desktop-style workspace around Linux RootFS images, per-Capsule storage, and a developing Windows compatibility runtime.

> **Development status:** runtime installation and launch paths now exist in the source, but VYROX is not yet a verified replacement for a Windows PC. A successful APK build does not prove that Wine, Box64, Vulkan, or a Windows application works on a physical phone. See the implementation and device-validation matrix below.

## Desktop experience

The landscape-first desktop opens to a wallpaper-style workspace. Its settings and navigation expose Capsule management, C:/D: file management, installer import, RootFS/Box64/Wine controls, runtime status, and Arabic/English language selection. Opening a Capsule provides its own files and installed-program list. A Windows session is presented in an in-app desktop viewer rather than launched as an Android application.

## Implemented source paths

| Area | Current source implementation |
|---|---|
| Android application | Kotlin, Jetpack Compose, Android document picker, app-private storage |
| Desktop shell | Desktop workspace, settings panel, navigation to Capsules, files, installer library and runtime tools; Arabic RTL and English LTR |
| Capsule workspaces | Separate logical C:/D: folders; create, rename, clone, backup, checksummed snapshots and restore |
| File manager | Browse, import, create, copy, move, rename, search, delete and safe ZIP extraction within a Capsule |
| Installer inspection | EXE/PE, MSI and ZIP inspection; app-private library; validated x86/x86-64 EXE or MSI copy to Capsule C:\\Downloads and launch through the Capsule Wine prefix |
| Linux RootFS | TAR/TAR.GZ/TAR.XZ import, staged extraction, path/size validation and metadata |
| PRoot | Pinned Android native build and APK packaging workflow; guest command planner, bind-mount validation, bounded logs and process stop/timeout |
| Box64 | Pinned v0.4.4 source-build action inside an ARM64 glibc RootFS, with ELF/version and minimal x86-64 syscall smoke checks |
| Wine | WineHQ stable amd64 package-install script through Box64; checks Wine 11+ loader metadata |
| Wine Prefix | Per-Capsule staging, real `wineboot -u`, registry/drive-link verification and atomic activation |
| EXE/MSI path | Capsule file-manager Run action; EXE starts through Wine, MSI uses `msiexec /i` |
| Program discovery | Scans machine-wide and per-user install folders (including AppData, Desktop and Start Menu); extracts executable icons for the Capsule app list |
| Windows display/input | Xvfb + Fluxbox + localhost x11vnc; RFB framebuffer viewer, touch pointer/drag, keyboard text and key events |
| Audio | PulseAudio PCM monitor bridge to Android AudioTrack |
| Graphics translation | Pinned DXVK 3.1.1 and VKD3D-Proton 3.1 install plan with Vulkan preflight and DLL/metadata verification |

**The rows above describe source implementation, not end-to-end device certification.** The runtime may still fail on a phone because of Android/PRoot ptrace behavior, Box64 ABI/library compatibility, Wine package layout, Vulkan driver availability, or application-specific Windows dependencies.

## What is still unverified or incomplete

- Build and run Box64 on a real Android phone and test a general x86-64 Linux workload beyond the minimal syscall smoke program.
- Install WineHQ stable in a real imported RootFS, create a prefix with real `wineboot`, and confirm Wine version/registry/drive mappings on-device.
- Install and launch redistributable EXE and MSI test applications on a physical ARM64 phone.
- Verify VNC screen updates, mouse/keyboard input and Android audio bridge during a real Windows session.
- Confirm DXVK/VKD3D DLLs install successfully in a real prefix, then run Direct3D 9/11/12 test workloads against the device's actual Vulkan driver.
- Add a real Android GPU/Vulkan bridge where the device and driver support it. Detecting Vulkan or installing translation DLLs alone is not proof of hardware acceleration.
- Improve isolation. Capsules and PRoot currently provide file separation and rootless Linux path redirection; they are not VMs or hardened security sandboxes.

## Runtime boundaries

- **PRoot** provides Linux filesystem/process redirection. It does not provide Windows APIs, CPU translation, Android root, or a security boundary.
- **Box64** translates x86-64 Linux ELF programs on ARM64 Linux. It does not load Windows PE files by itself.
- **Wine** provides Windows API compatibility. It must run inside the compatible Linux RootFS through the selected CPU translation path.
- **DXVK** translates selected Direct3D 8/9/10/11 interfaces to Vulkan. **VKD3D-Proton** targets Direct3D 12. Neither replaces Wine or Box64.
- The graphics installer records a backend only after the guest-side Vulkan probe reports a device/software backend and the expected DLL files exist. Real rendering still requires a game or graphics test on the target phone.

Capsule backups and snapshots are file-level archives. They do not capture a running process, live Windows memory, or a virtual-machine image. Do not run untrusted installers under the assumption that a Capsule is a malware sandbox.

## Build and CI

Requirements:

- JDK 17
- Android SDK Platform 37 and Build Tools 36.0.0
- Android NDK 27.2.12479018
- CMake 3.22.1
- Gradle 9.6.0
- Android Gradle Plugin 9.3.0
- Kotlin 2.4.21

Run from the repository root:

```bash
gradle :runtime-engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

GitHub Actions runs Python source-manifest checks, the pinned Android PRoot native build, JVM/unit tests, Android lint and debug APK assembly. A debug APK is uploaded only after the required job steps pass. Failed Gradle output is collected into a build-diagnostic artifact.

## Project layout

```text
app/                 Android UI, storage, VNC/audio bridges and runtime orchestration
runtime-engine/      RootFS, Box64, Wine, prefix and graphics installation plans
scripts/             Reproducible native build and source validation helpers
third_party/         Pinned source and license metadata
docs/                Architecture, runtime and third-party component documentation
.github/workflows/   Android CI
```

## License

VYROX application source is licensed under [Apache-2.0](LICENSE). Runtime dependencies retain their own licenses; see [third-party component notes](docs/THIRD_PARTY_COMPONENTS.md). Before shipping runtime binaries, include the matching upstream license notices and review redistribution obligations.
