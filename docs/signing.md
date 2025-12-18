# Signing & Security Architecture

This document describes the CI/CD signing architecture for Letterbox, including the separation of signing keys for different build types and security considerations for test builds.

## Overview

Letterbox uses a secure signing pipeline that isolates signing keys by build type:

- **Test Builds (PRs):** Signed with a separate, untrusted test key
- **Pre-releases (Main Branch):** Signed with the production release key
- **Releases (Tags):** Signed with the production release key

This separation ensures that untrusted code from pull requests never has access to production signing keys.

## Build Types & Version Naming

| Build Type | Trigger | Version Name Format | Application ID | Example |
|------------|---------|---------------------|----------------|---------|
| **Release** | Tag `v*` | `v1.2.3` | `org.joefang.letterbox` | `v1.2.3` |
| **Pre-release** | Main branch push | `v1.2.3-dev.N+hash` | `org.joefang.letterbox` | `v1.2.4-dev.5+a1b2c3d` |
| **Test** | Pull request | `ci-test-untrusted-1.2.3-dev.N+hash` | `org.joefang.letterbox.test` | `ci-test-untrusted-1.2.4-dev.5+a1b2c3d` |

## ⚠️ Important: About Test Builds

**Test builds (`.test` variants) are UNTRUSTED and signed with a separate keypair.**

These builds:
- Are automatically generated for every pull request
- Have the application ID `org.joefang.letterbox.test` (can be co-installed with production)
- Are signed with a separate test signing key stored in the `ci:test` environment
- Have version names prefixed with `ci-test-untrusted-` to make their untrusted nature explicit
- Are available as GitHub Actions artifacts for testers to download and install

### Why separate signing keys?

The test signing key is completely separate from the production release key because:

1. **Security Isolation:** PRs can contain arbitrary code. By using a separate key, we ensure that even if malicious code somehow extracted signing secrets, it would only have access to the test key, not the production key.

2. **Trust Separation:** Users and testers can clearly distinguish between:
   - **Trusted releases** signed with the production key
   - **Untrusted test builds** signed with the test key

3. **Automated PR Testing:** Since test builds use a separate key, they can be signed automatically without maintainer approval, enabling faster testing feedback.

### Installing Test Builds

1. Go to the GitHub Actions page for the pull request
2. Find the "Sign Test APK" workflow run
3. Download the `letterbox-staging-signed-apk` artifact
4. Install the APK on your device

**Note:** Since test builds have a different application ID (`.test` suffix), they can be installed alongside the production app without conflicts.

## GitHub Environments

The signing pipeline uses two protected GitHub Environments:

| Environment | Purpose | Signing Key | Protection Rules |
|-------------|---------|-------------|------------------|
| `ci:test` | Sign untrusted PR builds | Test key | No restrictions (automated) |
| `ci:release` | Sign main/tag builds | Production key | Branch: `main`, `v*` |

### Environment Secrets

Each environment contains:
- `ANDROID_KEYSTORE_B64`: Base64-encoded keystore file
- `ANDROID_KEYSTORE_PASSWORD`: Keystore/key password

Each environment contains (as variables):
- `ANDROID_KEY_ALIAS`: Key alias in the keystore

## Workflow Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Build Workflow                          │
│                    (No signing keys access)                     │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ Sets CI_BUILD_TYPE based on trigger:                        ││
│  │ - Pull Request → "test"                                     ││
│  │ - Main Branch → "prerelease"                               ││
│  │ - Tag v* → "release"                                       ││
│  └─────────────────────────────────────────────────────────────┘│
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ Builds unsigned APKs with appropriate version names         ││
│  │ Uploads as artifacts                                        ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
        ┌──────────────────────┴──────────────────────┐
        │                                              │
        ▼                                              ▼
┌───────────────────────────┐            ┌───────────────────────────┐
│    Sign Test Workflow     │            │   Pre-release Workflow    │
│   (ci:test environment)   │            │  (ci:release environment) │
│                           │            │                           │
│ - Downloads test APK      │            │ - Downloads prod APK      │
│ - Signs with TEST key     │            │ - Signs with PROD key     │
│ - Uploads signed artifact │            │ - Creates pre-release     │
│                           │            │                           │
│ Trigger: PR completed     │            │ Trigger: Main branch      │
└───────────────────────────┘            └───────────────────────────┘
                                                        │
                                                        │ (For tags)
                                                        ▼
                                         ┌───────────────────────────┐
                                         │   Release Workflow        │
                                         │  (ci:release environment) │
                                         │                           │
                                         │ - Builds prod APK         │
                                         │ - Signs with PROD key     │
                                         │ - Uploads to release      │
                                         └───────────────────────────┘
```

## Security Model

### Least Privilege Principle

- **Build workflow:** Has no access to signing keys (read-only permissions)
- **Sign workflows:** Only have access to their respective environment's keys
- **Artifacts:** Unsigned APKs are passed between workflows

### Protection Against Malicious PRs

The `workflow_run` trigger ensures that signing workflows:
1. Run the workflow code from `main`, not from the PR
2. Never execute untrusted code from the PR
3. Only process artifact data (not code) from the PR

This prevents malicious PR code from:
- Accessing signing secrets
- Modifying the signing workflow logic
- Exfiltrating credentials

## Key Generation

Keys should be generated with sufficient validity and security:

```bash
# Test key
keytool -genkeypair -v \
  -keystore letterbox-test.jks \
  -storetype JKS \
  -alias test \
  -keyalg RSA \
  -keysize 4096 \
  -validity 1048576 \
  -storepass "<password>" \
  -keypass "<password>" \
  -dname "CN=Test,O=Letterbox,OU=CI,L=Toronto,ST=Ontario,C=CA"

# Release key (use different, secure password)
keytool -genkeypair -v \
  -keystore letterbox-release.jks \
  -storetype JKS \
  -alias release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 1048576 \
  -storepass "<secure-password>" \
  -keypass "<secure-password>" \
  -dname "CN=Release,O=Letterbox,OU=Mobile,L=Toronto,ST=Ontario,C=CA"

# Base64 encode for GitHub secrets
base64 -w 0 letterbox-test.jks > letterbox-test.jks.b64
base64 -w 0 letterbox-release.jks > letterbox-release.jks.b64
```

**Important:** Never commit `.jks` files to the repository. Store them securely offline.

## Compliance Summary

1. ✅ **Separate signing keys:** Test and release keys are completely isolated
2. ✅ **Environment protection:** Keys are stored in protected GitHub Environments
3. ✅ **No PR access to production keys:** PRs can only trigger test signing
4. ✅ **Clear version naming:** Test builds have explicit `ci-test-untrusted-` prefix
5. ✅ **Co-installation support:** Different application IDs allow side-by-side installation
6. ✅ **Automated test signing:** PR test builds are signed automatically without approval
7. ✅ **Artifact availability:** Signed test APKs are uploaded for tester access
