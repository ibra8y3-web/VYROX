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
- Unit tests for installer extension validation and filename sanitization.
- GitHub Actions workflow for unit tests, Android lint, and debug APK assembly.

These features are implemented in source. GitHub Actions is the verification gate; no feature is described as build-verified until a passing run confirms it.

## Not implemented

- VM-grade Capsule isolation and independent Windows runtime environments (current Capsules are file-based storage workspaces only).
- Wine or another Windows API compatibility layer.
- x86/x64-to-ARM64 translation.
- Vulkan, DXVK, or DirectX translation.
- Windows process execution or application launch.
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
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

GitHub Actions runs the same checks on pushes and pull requests. When successful, it uploads `vyrox-debug-apk` as a workflow artifact. Dependabot checks Gradle and GitHub Actions dependencies weekly.

## Structure

```text
.github/workflows/android.yml
app/src/main/
  AndroidManifest.xml
  java/com/vyrox/runtime/
    MainActivity.kt
    core/files/
      InstallerPolicy.kt
      InstallerStorage.kt
    ui/VyroxApp.kt
  res/values/
app/src/test/java/com/vyrox/runtime/core/files/
  InstallerPolicyTest.kt
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
2. **Capsule workspaces:** create/list/remove private file-based workspaces and persist per-Capsule metadata; this does not provide process or VM isolation.
3. **Runtime research and integration:** evaluate Wine and CPU translation options, licenses, ABI requirements, and device compatibility.
4. **Graphics and input:** validate Vulkan/GPU paths on supported devices.
5. **Execution milestone:** launch a small, explicitly supported Windows test application on physical ARM64 hardware.
6. **Hardening:** security review, resource limits, diagnostics, and reproducible builds.

Every runtime feature must have documented device coverage and test evidence before being marked complete.

## Contributing

Please open an issue before proposing a runtime engine or binary dependency. Every integration must document its upstream source, license, supported ABI, build procedure, and test evidence.

## License

Apache-2.0. See [LICENSE](LICENSE).
