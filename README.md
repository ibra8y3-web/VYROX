# VYROX

**An open-source Windows application runtime environment for Android.**

VYROX is hosted by Android. It is not a replacement operating system. The project aims to provide managed workspaces ("Capsules") and, in later milestones, a tested compatibility runtime for selected Windows applications.

> **Current status: foundation milestone.** This initial implementation provides an Android app shell and a private installer-import workflow for `.exe` and `.msi` files. It does not execute Windows programs. Wine, CPU translation, graphics translation, and Windows API compatibility are not integrated.

## Implemented in this milestone

- Native Android application shell built with Kotlin and Jetpack Compose.
- System file picker for selecting EXE/MSI installers.
- Private app-internal storage for imported installer files.
- File-extension and file-size validation.
- Temporary-file copy followed by atomic rename to avoid exposing partial imports.
- Unit tests for installer validation and storage naming rules.
- GitHub Actions workflow for Android unit tests, lint, and debug APK assembly.

## Not implemented yet

- Capsule lifecycle and independent runtime environments.
- Wine or another Windows API compatibility layer.
- x86/x64-to-ARM64 translation.
- Vulkan, DXVK, or DirectX translation.
- Windows process execution or application launch.
- A hardened VM or security sandbox.

## Build

Requirements: JDK 17, Android SDK Platform 35, and Gradle 8.13.

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The repository workflow installs the JDK and Gradle and runs these checks on pushes and pull requests.

## Structure

```text
app/src/main/java/com/vyrox/runtime/
  MainActivity.kt
  core/files/InstallerImport.kt
  ui/VyroxApp.kt
app/src/test/java/com/vyrox/runtime/core/files/
  InstallerImportTest.kt
.github/workflows/android.yml
```

## Security notes

Imported files are stored in the application's private internal directory and are not executed. The import process is not malware scanning. Only import files from sources you trust. The initial storage boundary is Android app-private storage; it must not be described as a virtual machine or a hardened isolation boundary.

## Roadmap

1. Foundation app, installer import, tests, and CI.
2. Capsule creation, listing, metadata, and deletion.
3. Evaluate and integrate a licensed Windows compatibility layer and CPU translation backend.
4. Validate graphics and input paths on real supported ARM64 devices.
5. Demonstrate execution of a specifically supported Windows test application on physical hardware.
6. Security review, resource limits, diagnostics, and reproducible builds.

Every runtime feature must have documented device coverage and test evidence before being marked complete.

## License

Apache-2.0. See [LICENSE](LICENSE).
