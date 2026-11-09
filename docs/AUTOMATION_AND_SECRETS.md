# Build automation and secrets

## What runs automatically

The Android CI workflow runs on pushes to `main`, pull requests targeting
`main`, and manual dispatch. It executes:

1. Python unit tests for the diagnostic parser.
2. JVM unit tests.
3. Android lint.
4. Debug APK assembly.
5. APK artifact upload after all checks pass.
6. On failure, a deterministic Markdown diagnostic artifact containing first error clues, failed Gradle tasks, and recent log lines.

Dependabot checks Gradle dependencies and GitHub Actions versions weekly and
opens pull requests for updates.

## What CI does not do

A failed build does not automatically authorize a bot to rewrite application
source code. The workflow generates a deterministic diagnostic artifact from the captured Gradle log. It identifies likely failure categories and source locations, but it does not write or commit application code. A code change must still be produced, reviewed, committed, and rebuilt. This prevents a
misdiagnosed log line or an untrusted build artifact from silently changing
the application.

A model-powered repair workflow would require a separately configured API
credential, explicit spending limits, narrowly scoped repository permissions,
and safeguards that validate proposed patches. No model API key is required for
the deterministic diagnostic report, and none is stored in this repository.

## Secrets policy

- Never commit tokens, passwords, signing keys, or API keys.
- Do not put secrets in source files, Gradle files, README files, or build logs.
- Add a repository secret only when a specific integration actually needs it:
  GitHub repository → Settings → Secrets and variables → Actions.
- Use the smallest possible permission set and rotate a credential if it is
  exposed.
- Android debug builds do not require signing secrets. Release signing will be
  configured separately when a release build is introduced.

## Runtime dependencies

Third-party runtime engines are not bundled until their licensing, ABI,
native build process, package size, and Android compatibility have been
checked. Wine, CPU translation, Vulkan, DXVK, and Windows execution remain
separate milestones and are not claimed as integrated.
