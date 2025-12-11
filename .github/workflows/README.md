# GitHub Actions Workflows

This directory contains the CI/CD workflows for the Letterbox project. The workflows have been split into three separate files following security best practices and the principle of least privilege.

## Workflows Overview

### 1. `pull-request.yml` - Pull Request Checks
**Trigger:** When a pull request is opened or updated targeting the `main` branch.

**Permissions:** `contents: read` (read-only)

**Purpose:** Validates code quality and functionality for pull requests without granting write permissions.

**Jobs:**
- **lint-and-test**: Runs Rust format checks, Rust tests, Android lint, and Android unit tests
- **build**: Builds a debug APK for verification

**Security Notes:**
- No `contents: write` permission - follows security best practice for PR workflows
- Artifacts are retained for 7 days only (shorter retention for PR builds)
- APK artifacts are namespaced with PR number to avoid conflicts

### 2. `main-branch.yml` - Main Branch Build
**Trigger:** When code is pushed to `main` or `copilot/**` branches.

**Permissions:** `contents: write` (scoped to pre-release job only)

**Purpose:** Builds and tests code on main branch, creates pre-releases for testing.

**Jobs:**
- **lint-and-test**: Full validation suite (overrides permission to `contents: read`)
- **build**: Builds both debug and release APKs (overrides permission to `contents: read`)
- **pre-release**: Creates automated pre-releases (uses `contents: write`)

**Security Notes:**
- Global `contents: write` permission, but individual jobs override to `read` where possible
- Only the `pre-release` job actually needs write permissions
- Pre-releases are only created from the `main` branch (not copilot branches)
  - `copilot/**` branches trigger lint/test/build for validation but skip pre-release creation
  - This allows development branches to be tested without creating unnecessary releases
- Artifacts retained for 30 days

### 3. `release.yml` - Release Publication
**Trigger:** When a GitHub release is published.

**Permissions:** `contents: write` (scoped to publish job only)

**Purpose:** Builds and uploads the final release APK to the GitHub release.

**Jobs:**
- **build**: Compiles the release APK (overrides permission to `contents: read`)
- **publish**: Uploads APK to the release (uses `contents: write`)

**Security Notes:**
- Global `contents: write` permission, but build job overrides to `read`
- Only the `publish` job actually needs write permissions
- Artifacts retained for 90 days (longer retention for releases)

## Security Improvements

The workflow separation provides the following security benefits:

1. **Least Privilege for PRs**: Pull request workflows have no write permissions, preventing malicious PRs from modifying repository contents or creating releases.

2. **Scoped Permissions**: Each workflow has the minimum permissions needed for its specific purpose.

3. **Job-Level Permission Overrides**: Within workflows that need some write permissions, individual jobs override to read-only where possible.

4. **Clear Separation of Concerns**: Different workflows for different purposes makes it easier to audit and maintain security policies.

5. **Reduced Attack Surface**: Compromised PR builds cannot escalate privileges or modify the repository.

## Build Process

All workflows follow a similar build process:

1. **Setup**: Install JDK, Android SDK, Rust toolchain, and cargo-ndk
2. **Lint**: Run format checks on Rust code and Android lint
3. **Test**: Execute Rust unit tests and Android unit tests (including FFI integration tests)
4. **Build Native Libraries**: Compile Rust code for Android targets (arm64-v8a, armeabi-v7a, x86_64)
5. **Build APK**: Assemble debug and/or release APK files
6. **Artifact Upload**: Store APKs as GitHub Actions artifacts

## Artifact Retention Policies

- **PR builds**: 7 days (short-lived, for immediate verification)
- **Main branch builds**: 30 days (for ongoing testing)
- **Release builds**: 90 days (for long-term availability)

## Testing Locally

To test changes to these workflows locally, you can use [act](https://github.com/nektos/act):

```bash
# Test PR workflow
act pull_request -W .github/workflows/pull-request.yml

# Test main branch workflow
act push -W .github/workflows/main-branch.yml

# Test release workflow
act release -W .github/workflows/release.yml
```

Note: Local testing requires Docker and may not perfectly replicate the GitHub Actions environment.
