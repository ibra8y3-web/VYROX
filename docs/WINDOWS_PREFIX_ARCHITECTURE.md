# Windows Wine Prefix Architecture

## Current status

**Prefix storage lifecycle and integrity validator implemented; Wine itself is not integrated.** `WinePrefixManager` can stage a per-Capsule prefix, validate the actual registry files and default drive links produced by a real `wineboot -u`, redirect C:/D: to the Capsule's guest mount targets, then atomically activate and inspect the prefix. The selected Capsule's UI now reads and displays only a verified prefix record from its ARM64 RootFS. It never fabricates Registry files. No app action invokes Wine yet, so VYROX does not currently create a valid Wine prefix, install a Windows application through Wine, or launch a Windows PE file. An empty directory with a `system.reg` placeholder is not a Wine prefix and must never be shown as ready.

The current target is an ARM64 Android device running an imported ARM64 glibc Linux RootFS under the CI-built Android PRoot package. Box64 has a pinned in-RootFS build action that will require a successful x86-64 syscall smoke test before the app marks it installed.

## Chosen compatibility path

1. **Android host:** arm64-v8a, no Android root required.
2. **Linux guest:** an imported ARM64 Debian/Ubuntu glibc RootFS.
3. **Linux x86-64 translator:** Box64 v0.4.4 inside that RootFS.
4. **Windows API compatibility:** Wine 11.0 x86-64 WoW64, loaded through Box64.
5. **Prefix:** a separate, real Wine prefix for each Capsule.
6. **Graphics:** begin with Wine's software/fallback path; add DXVK only after device Vulkan and driver validation.

Wine 11.0 was released on January 13, 2026. Its new WoW64 architecture is considered fully supported, can run 32-bit Windows applications within a 64-bit prefix, and uses a single `wine` loader rather than the former separate `wine64` loader. Wine's release notes state that pure 32-bit prefixes are deprecated in the new WoW64 mode. See the [Wine 11.0 release notes](https://www.winehq.org/news/2026011301).

Box64 translates x86-64 **Linux ELF** programs; it does not load Windows PE files. Wine supplies the Windows API layer. Both components must be present and separately verified before Windows execution can be attempted. See [Box64's upstream usage and Wine guidance](https://github.com/ptitSeb/box64#usage).

## Per-Capsule prefix layout

The proposed guest-side layout is:

```text
/opt/vyrox/
  prefixes/
    <capsule-id>/
      drive_c/             # linked to the Capsule's persistent C: contents
      dosdevices/
        c: -> ../drive_c
        d: -> <Capsule D: mount>
        z: -> /
      system.reg
      user.reg
      userdef.reg
      user/
      wineserver
      .vyrox-prefix.properties
```

The final structure must follow the paths and symbolic-link semantics produced by the chosen Wine build's own `wineboot`. The sketch above is an architectural target, not a set of files the app should fabricate.

Each Capsule has its own prefix ID, registry, user profile, installed applications, configuration and snapshot lifecycle. Prefix creation must never reuse another Capsule's `WINEPREFIX`.

The C: and D: drive targets must map only to that Capsule's app-private C:/D: storage. The bind source paths must be validated by the existing PRoot command planner and remain under approved VYROX storage roots. A mounted directory is a path mapping, not a security sandbox.

## Actual prefix creation and readiness gate

A future Wine manager must:

1. Confirm that the selected Capsule has a valid ARM64 glibc RootFS.
2. Confirm Box64's pinned metadata and a passed x86-64 smoke marker.
3. Verify the installed Wine package's source, version, architecture and license.
4. Execute the real Wine loader through Box64 with a new prefix path in the selected RootFS.
5. Let Wine create the prefix using `wineboot -u`; never manually forge Wine registry files.
6. Verify that the process exited successfully, required registry files exist and are readable, drive links resolve to the Capsule's own C:/D: mount points, and the Wine version can be queried from the same prefix.
7. Write VYROX prefix metadata only after the whole operation passes.
8. Keep a bounded process log and remove only a failed staging prefix; do not overwrite a working prefix on a partial failure.

The first implementation should use a 64-bit prefix compatible with Wine's new WoW64 path. Do not create legacy `WINEARCH=win32` prefixes for this architecture.

## Package acquisition and multiarch constraints

The Wine binary needed by Box64 is an **amd64 Linux ELF** plus its x86-64 runtime libraries. An ARM64 RootFS cannot execute that binary natively. APT's multiarch system can install foreign-architecture packages, but only when the configured repository publishes that architecture and the package dependencies are coherent. Debian documents adding a foreign architecture with `dpkg --add-architecture`; it also warns that multiarch application packages and libraries need matching package versions. See the [Debian Wine guide](https://wiki.debian.org/Wine) and [Debian Multiarch HOWTO](https://wiki.debian.org/Multiarch/HOWTO).

The future installer must inspect the RootFS distribution, codename, configured APT source architectures and package candidate versions before changing package configuration. It must stop with a clear diagnostic when the official repository does not publish the selected distribution/architecture. It must not blindly mix Debian suites, Ubuntu suites, WineHQ repositories or third-party packages.

Wine 11.0 is the selected initial target because of its completed WoW64 mode. The exact package source, signing key, package version and dependencies must still be pinned and checked for the selected RootFS before a Wine installer is enabled.

## Installing and launching EXE/MSI after Wine integration

The planned sequence is:

- Keep the imported EXE/MSI in VYROX's app-private installer store.
- Bind only that selected installer file into the selected Capsule's guest session.
- Start the ARM64 Box64 binary inside PRoot, passing the x86-64 Wine loader and the selected prefix.
- For EXE, invoke the Wine loader with the guest-visible installer path.
- For MSI, invoke the same Wine loader with its `msiexec` entry point and the installer path.
- Wait for process exit, preserve a bounded log, report the actual exit status and refresh the Capsule's file listing.
- Never call the installer "installed" unless Wine's real prefix/application state confirms it.

No install/run buttons should be enabled until the Wine package and prefix readiness gates pass.

## Engine status

| Component | Current state |
|---|---|
| Android PRoot source build and APK packaging | Built and ELF-checked by CI; physical-device ptrace/execve still unverified |
| ARM64 glibc RootFS import/validation | Implemented; physical-device execution still unverified |
| Box64 v0.4.4 source build | Wired into the app; requires a successful in-RootFS build and smoke test when run |
| Wine 11.0 WoW64 | Architecture chosen; binaries/packages not integrated |
| Per-Capsule Wine prefixes | Transactional storage/verification manager, symlink-ticket protection, tests and UI integrity status implemented; no real prefix is created until Wine/wineboot is integrated |
| EXE/MSI installation and launch | Not integrated |
| DXVK/VKD3D-Proton | Not integrated |
| Keyboard, pointer, controller, window and audio bridges | Not integrated |

## Non-goals and safety boundary

PRoot's guest-only fake-root identity does not grant Android superuser access and is not a security boundary. Capsules and Wine prefixes provide separate storage and runtime configuration, not hardened isolation from hostile applications. Do not run untrusted installers without an appropriate isolation and risk model.
