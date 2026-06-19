# GitHub Actions Workflows

This directory contains the CI/CD workflows for the Letterbox project. The workflows follow security best practices with principle of least privilege and consolidated build steps for efficiency.

## Workflows Overview

### 1. `build.yml` - Unified Build Workflow
**Trigger:** Push to `main`/`copilot/**` branches, pull requests to `main`, or called by other workflows.

**Permissions:** `contents: read` (read-only)

**Purpose:** Central build workflow that handles linting, testing, and APK compilation. Other workflows can trigger on its completion to avoid rebuilding.

**Jobs:**
- **lint-and-test**: Runs Rust format checks, Rust tests, Android lint, and Android unit tests
- **build**: Builds staging debug and prod release APKs with native libraries for all architectures

**Outputs:**
- `test-artifact-name`: Name of the uploaded staging debug APK artifact
- `prod-release-artifact-name`: Name of the uploaded prod release (unsigned) APK artifact

### 2. `android-ui.yml` - Android UI Tests
**Trigger:** On completion of the Build workflow (via `workflow_run`).

**Permissions:** `contents: read`, `actions: read`

**Purpose:** Runs instrumented UI tests using Gradle Managed Devices with hardware acceleration (KVM).

**Jobs:**
- **ui-tests**: Runs comprehensive UI tests on an emulated Pixel 7 (API 34)

**Test Coverage:**
- Home screen validation (title, buttons, dialogs)
- Navigation flows (menu interactions, state consistency)
- Accessibility checks (content descriptions, element counts)

### 3. `sign-test.yml` - Sign Test APK
**Trigger:** On successful completion of Build workflow (PRs only).

**Permissions:** `contents: read`, `actions: read`

**Environment:** `ci:test` (uses separate test signing key)

**Purpose:** Signs PR test builds with a separate, untrusted test signing key. This allows testers to download and install test builds from PRs without giving malicious PR code access to production signing keys.

**Security Notes:**
- Uses a completely separate signing keypair from production releases
- Version name includes `ci-test-untrusted-` prefix to clearly indicate untrusted status
- Signed APKs are uploaded as artifacts for testers to download
- See [docs/signing.md](../../docs/signing.md) for full security documentation

### 4. `pre-release.yml` - Pre-release Creation
**Trigger:** On successful completion of Build workflow (main branch only).

**Permissions:** `contents: write`, `actions: read`

**Environment:** `ci:release` (uses production signing key)

**Purpose:** Creates automated pre-releases from main branch builds by downloading APK artifacts from the build workflow.

### 5. `release.yml` - Release Publication
**Trigger:** When a GitHub release is published.

**Permissions:** `contents: write` (scoped to publish job only)

**Environment:** `ci:release` (uses production signing key)

**Purpose:** Builds and uploads the final release APK to the GitHub release.

**Jobs:**
- **build**: Compiles the release APK (overrides permission to `contents: read`)
- **publish**: Uploads APK to the release (uses `contents: write`)

## Workflow Dependencies

```
┌─────────────────────────────────────────────────────────────┐
│                    Push / Pull Request                       │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
              ┌───────────────────────┐
              │      build.yml        │
              │  (lint, test, build)  │
              │  Sets CI_BUILD_TYPE   │
              └───────────┬───────────┘
                          │
          ┌───────────────┼───────────────┬───────────────┐
          │               │               │               │
          ▼               ▼               ▼               ▼
┌─────────────────┐ ┌───────────────┐ ┌───────────────┐ ┌─────────────────┐
│ android-ui.yml  │ │sign-test.yml  │ │pre-release.yml│ │  (other future  │
│   (UI tests)    │ │ (PRs only)    │ │ (main only)   │ │   consumers)    │
│                 │ │ ci:test env   │ │ ci:release env│ │                 │
└─────────────────┘ └───────────────┘ └───────────────┘ └─────────────────┘
```

## Security Improvements

1. **Least Privilege for PRs**: Build workflow has no write permissions.
2. **Separate Signing Keys**: Test builds use `ci:test` environment with separate key, releases use `ci:release`.
3. **Consolidated Building**: APKs are built once and shared via artifacts.
3. **Job-Level Permission Overrides**: Within workflows that need write permissions, individual jobs override to read-only where possible.
4. **Clear Separation of Concerns**: Different workflows for different purposes makes it easier to audit and maintain security policies.

## Build Process

All builds follow a similar process:

1. **Setup**: Install JDK, Android SDK, Rust toolchain, and cargo-ndk
2. **Lint**: Run format checks on Rust code and Android lint
3. **Test**: Execute Rust unit tests and Android unit tests (including FFI integration tests)
4. **Build Native Libraries**: Compile Rust code for Android targets (arm64-v8a, armeabi-v7a, x86_64)
5. **Build APK**: Assemble debug and/or release APK files
6. **Artifact Upload**: Store APKs as GitHub Actions artifacts

## UI Testing

The `android-ui.yml` workflow runs instrumented tests using Gradle Managed Devices:

```bash
# Run UI tests locally
./gradlew pixel7Api34StagingDebugAndroidTest
```

Test files are located in `app/src/androidTest/java/org/joefang/letterbox/`:
- `HomeScreenTest.kt` - Core UI element tests
- `NavigationTest.kt` - Navigation flow tests
- `BackNavigationTest.kt` - Back navigation behavior tests
- `AccessibilityTest.kt` - Accessibility compliance tests
- `EmailDetailScreenTest.kt` - Email detail screen tests
- `EmailOpeningE2ETest.kt` - End-to-end email opening tests
- `AttachmentInteractionE2ETest.kt` - Attachment interaction tests
- `HistoryFeaturesE2ETest.kt` - History feature tests
- `LinkHandlingE2ETest.kt` - Link handling tests
- `CloudflareTermsConsentE2ETest.kt` - Cloudflare consent flow tests
- `ImageProxyIntegrationTest.kt` - Image proxy integration tests

## Artifact Retention Policies

- **Build artifacts**: 30 days
- **UI test artifacts**: 14 days
- **Release builds**: 90 days

## Testing Locally

To test changes to these workflows locally, you can use [act](https://github.com/nektos/act):

```bash
# Test build workflow
act push -W .github/workflows/build.yml

# Test release workflow
act release -W .github/workflows/release.yml
```

Note: Local testing requires Docker and may not perfectly replicate the GitHub Actions environment. UI tests require KVM which is not available in Docker.
