# Box64 build inside a VYROX Capsule

## What this adds

VYROX includes an app action that builds Box64 v0.4.4 from the reviewed immutable upstream commit:

`2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a`

The commit is the upstream change that bumped Box64 to v0.4.4. See the [upstream commit](https://github.com/ptitSeb/box64/commit/2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a) and the [official compilation guide](https://github.com/ptitSeb/box64/blob/main/docs/COMPILE.md).

## Target and important boundary

Box64 is built as an **AArch64 Linux/glibc ELF executable inside the selected ARM64 Debian or Ubuntu RootFS**. It is not compiled as an Android/Bionic APK library.

Box64 translates x86-64 Linux ELF user-space programs on ARM64 Linux. It does **not** load Windows PE/EXE files. Windows applications still require Wine/WOW64 and their x86-64 runtime libraries, plus working display, audio and input integration.

## Run it in the app

1. Create or open a Capsule.
2. Import an ARM64 Debian or Ubuntu Linux RootFS archive.
3. Open the Capsule file explorer and locate the Linux RootFS panel.
4. Choose **Build Box64 0.4.4** beside the ARM64 image.

The build runs inside that RootFS using the Android PRoot executable. PRoot's `-0` option maps the guest process to uid/gid 0 **inside the guest only**. It does not grant Android superuser privileges and does not modify Android's system package database. The build request binds only `/dev/null`, `/dev/zero`, `/dev/urandom`, `/dev/random`, `/proc`, and a temporary resolver file populated from Android's active network DNS servers. That resolver file is stored in app-private cache, marked read-only and removed when the build exits.

## Build steps

The pinned script:

1. Requires an APT-based Debian/Ubuntu RootFS and checks managed source/build paths for symbolic links.
2. Installs GCC, CMake, Git, pkg-config and the selected Linux development headers inside the guest.
3. Fetches only the exact immutable Box64 commit and verifies the checked-out SHA.
4. Configures the ARM64 DynaRec build with the mixed Android/Linux-kernel `BAD_SIGNAL` option.
5. Builds with two jobs to reduce memory pressure and installs into `/usr/local` inside that RootFS.
6. Executes `box64 --version`, checks for v0.4.4, then writes the VYROX metadata record.
7. The Android app independently checks the metadata and AArch64 ELF machine/type before displaying Box64 as installed.

The official upstream compilation notes explain the ARM64/PRoot flags and DynaRec option.

## Network, storage and performance

The build needs a working network connection for APT and Git and sufficient free space for compilers, source, build objects and installed libraries. It can use substantial RAM and storage and is not guaranteed to finish on every phone. The app limits the runtime log to 8 MiB and the build process to one hour.

## Verified versus not yet verified

| Gate | Status in source |
|---|---|
| Immutable v0.4.4 upstream commit | Verified and pinned |
| App action, PRoot fake-root option and state refresh | Implemented; CI pending |
| Manifest and Kotlin tests | Added; CI pending |
| Box64 built on a real Android phone | Not yet verified |
| Box64 running an x86-64 Linux executable on the phone | Not yet verified |
| Wine/WOW64, Windows EXE/MSI installation and launch | Not integrated |
| DXVK/VKD3D-Proton and Direct3D graphics | Not integrated |
| Full Windows display, audio, keyboard and gamepad bridge | Not integrated |

A successful APK build only validates Android source compilation and packaging. It does not prove Box64 can build or execute on a given physical device.
