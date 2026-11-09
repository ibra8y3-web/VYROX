# VYROX

**An open-source Windows application runtime environment for Android.**

VYROX is hosted by Android. It is not a replacement operating system. The project aims to provide managed workspaces ("Capsules") and, in later milestones, a tested compatibility runtime for selected Windows applications.

> **Current status: foundation milestone.** The Android source includes file-based Capsule workspaces and can import `.exe` and `.msi` installers into private app storage, list them, and remove them. It does not execute Windows programs. Capsule folders are not virtual machines or hardened security sandboxes. Wine, CPU translation, graphics translation, and Windows API compatibility are not integrated.

## Implemented in source

- Native Android application shell built with Kotlin and Jetpack Compose.
- Adaptive Android launcher icon for supported Android launchers.
- System document picker for selecting installer files.
- Private app-internal storage for imported installers.
- File-extension validation and a 2 GiB import limit.
- Temporary-file copy followed by rename, so incomplete copies are not shown as imported.
- Local installer list and remove action.
- Capsule workspace creation, listing, metadata persistence, and deletion in app-private storage.
- Capsule file explorer with C:/D: navigation, folder creation, file import, and safe delete actions.
- File-backed C: and D: directory layouts inside each new Capsule (not mounted Windows drives).
- Device capability probe for supported Android ABIs, logical CPU count, total memory, and ARM64 host readiness.
- JNI/C++ native bridge built with Android NDK and CMake; reports native architecture, pointer width, memory page size, CPU count, and Android API level.
- Vulkan capability probe reports the physical device, API/driver versions, required DXVK extension names, push-constant size, and descriptor-indexing support when exposed by the driver.
- Dedicated `:runtime-engine` Kotlin/JVM module with an upstream engine catalog, Vulkan capability parser, and Windows x64 readiness planner.
- PE/COFF header inspection for imported EXE files; detects x86, x86-64, ARM32, and ARM64 machine types before storing the installer.
- MSI Compound File Binary signature check before storing an MSI database.
- SHA-256 verified runtime package installer with ABI checks, ZIP traversal protection, archive limits, and private atomic installation.
- Unit tests for installer policy, Capsule metadata, workspace layout, path containment, file import, file operations, engine planning, and package installation.
- GitHub Actions workflow for unit tests, Android lint, and debug APK assembly.
- Deterministic build-failure diagnostic report uploaded as a separate artifact when CI fails.

These features are implemented in source. GitHub Actions is the verification gate; no feature is described as build-verified until a passing run confirms it.

## Not implemented

- VM-grade Capsule isolation and independent Windows runtime environments (current Capsules are file-based storage workspaces only).
- Mountable Windows drive mapping; the current file explorer operates on app-private file-backed directories only.
- Wine or another Windows API compatibility layer.
- A bundled Linux RootFS, Wine prefix, Box64/FEX translator, DXVK, or VKD3D-Proton runtime.
- x86/x64-to-ARM64 translation.
- A Windows compatibility runtime, native CPU translator, or Windows process loader.
- DXVK, VKD3D-Proton, DirectX execution, or validation of required Vulkan extensions/features.
- Vulkan, DXVK, or DirectX translation.
- Windows process execution or application launch.
- Downloaded or bundled Wine/Box64/FEX/DXVK runtime packages; the package manager exists but no compatible packages are published or installed.
- A hardened VM or security sandbox.
- Malware scanning of imported installers.

## Build configuration

- JDK 17
- Android SDK Platform 36 (target SDK 35)
- Android Gradle Plugin 8.13.2
- Gradle 8.13
- Kotlin 2.2.21
- Jetpack Compose BOM 2025.08.00

Run from the repository root after installing the Android SDK platform and Gradle:

```bash
gradle :runtime-engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

GitHub Actions runs the same checks on pushes and pull requests. When successful, it uploads `vyrox-debug-apk` as a workflow artifact. When a job fails, it captures Gradle output, generates a diagnostic Markdown report, and uploads `vyrox-build-diagnostic`. Dependabot checks Gradle and GitHub Actions dependencies weekly.

## Structure

```text
.github/workflows/android.yml
scripts/diagnose_build_failure.py
scripts/tests/test_diagnose_build_failure.py
app/src/main/
  AndroidManifest.xml
  java/com/vyrox/runtime/
    MainActivity.kt
    core/files/
      InstallerPolicy.kt
      InstallerStorage.kt
      WindowsBinaryInspector.kt
    core/capsule/
      CapsulePolicy.kt
      CapsuleFilePolicy.kt
      CapsuleStorage.kt
    core/host/
      HostCapabilities.kt
      NativeHostBridge.kt
    core/engine/
      RuntimePackageManager.kt
    cpp/
      CMakeLists.txt
      vyrox_native.cpp
    ui/VyroxApp.kt
  res/values/
app/src/test/java/com/vyrox/runtime/core/files/
  InstallerPolicyTest.kt
app/src/test/java/com/vyrox/runtime/core/capsule/
  CapsulePolicyTest.kt
  CapsuleStorageTest.kt
app/src/test/java/com/vyrox/runtime/core/host/
  HostCapabilitiesTest.kt
app/src/test/java/com/vyrox/runtime/core/engine/
  RuntimePackageManagerTest.kt
runtime-engine/
  src/main/kotlin/com/vyrox/engine/RuntimeCatalog.kt
  src/test/kotlin/com/vyrox/engine/RuntimeCatalogTest.kt
build.gradle.kts
settings.gradle.kts
gradle.properties
LICENSE
```

## Security notes

- Imported files are stored in the application's private internal directory and are not executed.
- The import pipeline accepts only `.exe` and `.msi` filenames and enforces a maximum size.
- File import is not malware scanning. Do not import or execute untrusted files.
- The initial storage boundary is app-private storage, not a virtual machine or a hardened security boundary.

## Roadmap

1. **Foundation:** Android shell, safe installer import, tests, and CI.
2. **Capsule workspaces:** create/list/remove private file-based workspaces, persist metadata, and prepare C:/D: directory layouts; this does not provide process or VM isolation.
3. **Runtime architecture:** keep the engine catalog, capability parser, and readiness planner in the JVM module; validate PE/COFF architecture before storing installers.
4. **Linux userland and Windows compatibility:** build a reproducible ARM64 userland and Wine integration; then select a compatible x86-64 execution route. The planner currently reports missing prerequisites and does not launch anything.
5. **Graphics and input:** use the Vulkan extension/feature probe to assess DXVK and VKD3D prerequisites, then validate on specific physical GPUs.
6. **Execution milestone:** launch a small, explicitly supported Windows test application on physical ARM64 hardware.
7. **Hardening:** security review, resource limits, diagnostics, and reproducible builds.

Every runtime feature must have documented device coverage and test evidence before being marked complete.

## Contributing

Please open an issue before proposing a runtime engine or binary dependency. Every integration must document its upstream source, license, supported ABI, build procedure, and test evidence.

## License

Apache-2.0. See [LICENSE](LICENSE).
