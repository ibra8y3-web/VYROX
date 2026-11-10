# Windows Runtime, Wine Prefix and Desktop Integration

## Status at a glance

The repository now contains source paths for installing WineHQ stable, creating a real per-Capsule prefix, running EXE/MSI files through Wine and Box64, showing the Linux/Wine desktop through VNC, bridging PCM audio to Android, discovering installed executables, and installing pinned DXVK/VKD3D-Proton releases.

**These paths are implemented in source but still require successful CI and physical Android device tests.** Do not treat source code, a passing unit test, downloaded package, or DLL-presence check as proof that a Windows application runs correctly on a phone.

## Selected architecture

1. Android ARM64 hosts the VYROX app and its Android-compatible PRoot binary.
2. Each Capsule imports an ARM64 Debian/Ubuntu glibc RootFS.
3. Box64 is built inside that RootFS and checked as an AArch64 ELF plus a minimal x86-64 Linux syscall smoke test.
4. WineHQ stable amd64 packages are installed into the RootFS and the x86-64 Wine loader is invoked through Box64.
5. Each Capsule receives a separate Wine prefix under `/opt/vyrox/prefixes/<capsule-id>`.
6. The Capsule's existing app-private C:/D: folders are mounted at the guest drive targets.
7. EXE/MSI installers execute inside the selected prefix; the desktop viewer displays the X11 session over a localhost-only RFB/VNC connection.
8. DXVK/VKD3D-Proton are optional prefix-level graphics translation layers and only install after a guest-side Vulkan probe succeeds.

Box64 translates x86-64 Linux ELF programs; it does not load Windows PE files. Wine supplies the Windows API compatibility layer.

## Wine package installation

`WineRuntimePlan.createInstallShellScript()` is the source for the WineHQ installation action. It:

- Accepts the configured Debian/Ubuntu RootFS codenames explicitly supported by the script.
- Enables the amd64 package architecture inside that guest RootFS.
- Adds the WineHQ signing key and a WineHQ stable source scoped to the guest's OS ID/codename.
- Installs WineHQ stable, X11/VNC, PulseAudio, icon-extraction and Vulkan diagnostic packages.
- Finds the x86-64 Wine loader and Windows `wineboot.exe` payload.
- Checks the loader's ELF machine, Wine version (11 or newer), and the PE header of `wineboot.exe`.
- Writes the Wine metadata record only after those checks pass.

The package install is a guest RootFS operation under PRoot fake-root mapping. It does not grant Android root or modify Android's host package database. The exact package layout can vary by distribution and WineHQ packaging; a successful install action must still be confirmed on the target RootFS.

## Real per-Capsule prefix

`WinePrefixManager` provides a transactional lifecycle:

1. Validate the Capsule ID, existing C:/D: storage, RootFS and destination path.
2. Create a private staging directory for that Capsule's prefix.
3. Mount the Capsule's C: and D: directories at the expected guest paths.
4. Run the real Wine loader through Box64 with `WINEPREFIX` set to the staging directory and invoke `wineboot -u`.
5. Require successful process exit, real Wine version output, registry files and expected drive-link targets.
6. Atomically move the verified staging prefix into `/opt/vyrox/prefixes/<capsule-id>`.
7. Re-inspect it before the UI marks it ready.

The app must never create placeholder `system.reg` files or label a partially initialized directory as a Wine prefix. A failed stage is removed without overwriting an already working prefix.

## EXE and MSI flow

The user first imports the executable into the selected Capsule's C: or D: drive through the file manager. The Run action accepts EXE and MSI entries from that Capsule only:

- EXE: Box64 starts the x86-64 Wine loader with the selected guest file path.
- MSI: Box64 starts the same Wine loader with `msiexec /i <path>`.
- Both use the Capsule's verified `WINEPREFIX`, not a shared global prefix.

The process has bounded logs, an execution timeout and a Stop action. After the process exits, VYROX refreshes the Capsule's file listing and scans common installation folders for executable applications. Windows icons are extracted from PE resources with `wrestool`/`icotool` and the app list uses the detected executable paths. Discovery is heuristic; not every Windows application registers itself in one of the scanned folders.

The separate Installer Library stores imported setup files in app-private Android storage. To run one, the file must first be copied/imported into the selected Capsule's C: or D: drive.

## Desktop, input and audio path

The current source starts:

- Xvfb for the guest display.
- Fluxbox as the guest window manager.
- x11vnc bound to localhost only.
- A VYROX RFB 3.8 client that decodes the framebuffer into the Android viewer.
- Touch pointer, drag, keyboard key events and text-entry forwarding.
- A PulseAudio monitor pipeline that streams 48 kHz signed 16-bit stereo PCM to an Android AudioTrack bridge.

The viewer is an in-app view of the guest desktop. It is not a native Android window for each Windows process. These bridges still need on-device testing for display negotiation, latency, keyboard layouts, process lifecycle, and audio reliability.

## DXVK and VKD3D-Proton

`GraphicsTranslationPlan` pins:

- DXVK 3.1.1 for selected Direct3D 9/10/11 interfaces.
- VKD3D-Proton 3.1 for Direct3D 12.

The installation plan downloads the official release archives inside the RootFS, checks the archive contents, invokes the upstream setup scripts with Wine wrappers routed through Box64, and checks the expected 32-bit/64-bit DLL files in the Capsule's Windows directories. It writes prefix metadata only after those checks pass.

The Vulkan probe may report a software Vulkan backend such as lavapipe. That is not Android GPU acceleration. Presence of Vulkan, DXVK/VKD3D DLLs, or a successful installer script does not prove a Direct3D workload renders. Real game/software tests against the target Android device's Vulkan driver remain required.

## Readiness and validation matrix

| Layer | Source implementation | Still required |
|---|---|---|
| Android PRoot | CI builds and packages Android PRoot plus its loader | Physical-device ptrace/execve and RootFS shell tests |
| ARM64 RootFS | Import validation and staged extraction | Test supported Debian/Ubuntu images on phone |
| Box64 | Pinned build action, metadata checks and syscall smoke | Run build and general x86-64 Linux workload on phone |
| WineHQ stable | Guest installation script and loader checks | Confirm package paths and loader behavior on device |
| Wine Prefix | Real wineboot flow, registry/drive validation, atomic activation | Create and inspect prefix on device |
| EXE/MSI | Run action in selected Capsule prefix | Install and launch known-good Windows test apps on phone |
| Program icons | PE resource extraction and common-folder scan | Confirm icon extraction against installed software |
| Screen/input | Xvfb, Fluxbox, x11vnc, RFB viewer, touch/keyboard forwarding | Confirm rendering, resize, drag, key input and latency on device |
| Audio | PulseAudio PCM to Android AudioTrack | Confirm real audio stream and lifecycle on device |
| DXVK/VKD3D-Proton | Pinned archive install and DLL checks | Confirm driver support and D3D9/11/12 rendering workloads |
| Security isolation | App-private file separation and PRoot path mapping | Not VM-grade isolation; do not treat as a security sandbox |

## Security boundary

Capsules are logically separated file-backed workspaces. PRoot's guest fake-root identity is not Android superuser access and is not a hardened isolation boundary. Wine applications execute within the app-managed Linux guest process and may access the files made available to that process. Do not run untrusted Windows installers under the assumption that Capsule storage prevents malicious behavior.
