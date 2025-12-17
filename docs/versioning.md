# Versioning Schema

Letterbox uses an automated version code generation system based on Git tags. This document describes the schema and how it integrates with the build pipeline.

## Overview

The app uses a **30-bit version code schema** that ensures:
- Version codes always increase monotonically
- Dev builds after a release have higher version codes than the release
- Proper ordering: `v1.2.3 < v1.2.4-dev.1 < v1.2.4-dev.2 < ... < v1.2.4`
- Compliance with Google Play's 2.1 billion limit

## Version Code Schema

The version code is computed as a 30-bit integer with four components:

| Component   | Bits | Range    | Bit Position | Description                                |
|-------------|------|----------|--------------|---------------------------------------------|
| **Major**   | 7    | 0 – 127  | bits 23-29   | Major version number                       |
| **Minor**   | 7    | 0 – 127  | bits 16-22   | Minor version number                       |
| **Patch**   | 7    | 0 – 127  | bits 9-15    | Patch version number                       |
| **Qualifier** | 9  | 0 – 511  | bits 0-8     | 511 for stable releases, 0-510 for dev builds |

### Formula

```
versionCode = (major << 23) | (minor << 16) | (patch << 9) | qualifier
```

### Maximum Version Code

The maximum possible version code is:
```
(127 << 23) | (127 << 16) | (127 << 9) | 511 = 1,073,741,823
```

This is safely under Google Play's 2.1 billion limit.

## Version Types

### Stable Releases

When the build is exactly on a Git tag matching `v*.*.*`:

- **Version Name**: `MAJOR.MINOR.PATCH` (e.g., `1.2.3`)
- **Qualifier**: 511 (maximum)
- **Example**: `v1.2.3` → versionCode = `(1 << 23) | (2 << 16) | (3 << 9) | 511`

### Development Builds

When there are commits after the latest release tag:

- **Patch Increment**: The patch version is incremented by 1
- **Version Name**: `MAJOR.MINOR.PATCH-dev.N+HASH` (e.g., `1.2.4-dev.5+abc1234`)
- **Qualifier**: Number of commits since the last tag
- **Example**: 5 commits after `v1.2.3` → versionCode = `(1 << 23) | (2 << 16) | (4 << 9) | 5`

### Why Increment Patch for Dev Builds?

Consider this scenario:
- `v1.2.3` release has versionCode with patch=3, qualifier=511
- First commit after release needs versionCode > release

If we kept patch=3 and used qualifier=1:
```
v1.2.3:      (1 << 23) | (2 << 16) | (3 << 9) | 511 = 9,044,479
dev commit:  (1 << 23) | (2 << 16) | (3 << 9) | 1   = 9,043,969  ❌ LOWER!
```

By incrementing patch to 4:
```
v1.2.3:      (1 << 23) | (2 << 16) | (3 << 9) | 511 = 9,044,479
dev commit:  (1 << 23) | (2 << 16) | (4 << 9) | 1   = 9,044,481  ✅ HIGHER!
```

## Version Ordering Examples

The table below shows how version codes increase monotonically through the development cycle:

| Version | Major | Minor | Patch | Qualifier | Version Code | Notes |
|---------|-------|-------|-------|-----------|--------------|-------|
| v1.2.3 (stable)     | 1 | 2 | 3 | 511 | 8,521,727  | Release tagged v1.2.3 |
| v1.2.4-dev.1        | 1 | 2 | 4 | 1   | 8,521,729  | First commit after v1.2.3 |
| v1.2.4-dev.50       | 1 | 2 | 4 | 50  | 8,521,778  | 50 commits after v1.2.3 |
| v1.2.4 (stable)     | 1 | 2 | 4 | 511 | 8,522,239  | Release tagged v1.2.4 |
| v1.2.5-dev.1        | 1 | 2 | 5 | 1   | 8,522,241  | First commit after v1.2.4 |
| v1.3.0 (stable)     | 1 | 3 | 0 | 511 | 8,585,727  | Release tagged v1.3.0 (skips v1.2.5) |
| v1.3.1-dev.1        | 1 | 3 | 1 | 1   | 8,585,729  | First commit after v1.3.0 |
| v2.0.0 (stable)     | 2 | 0 | 0 | 511 | 16,777,727 | Major release v2.0.0 |

**Key observations:**
- Dev builds after v1.2.3 are labeled as v1.2.4-dev.N (working towards the next patch)
- When creating v1.3.0 instead of v1.2.5, the version code jumps appropriately
- All version codes strictly increase, ensuring upgrades are always detected by the system

## Git Tag Requirements

- Release tags **must** follow the pattern `v*.*.*` (e.g., `v1.0.0`, `v2.3.4`)
- The release workflow validates this pattern and rejects invalid tags
- Tags should only be created for actual releases (creating the tag triggers version code = 511)

## Limits and Constraints

### Maximum Commits Between Releases

The qualifier field allows up to 511 commits between releases. If you exceed this limit, the build will fail with an error instructing you to create a new version tag.

### Version Component Limits

| Component | Maximum Value | Error Condition |
|-----------|---------------|-----------------|
| Major     | 127           | Build fails if exceeded |
| Minor     | 127           | Build fails if exceeded |
| Patch     | 126           | Build fails if patch=127 and dev build needed (would overflow to 128) |
| Qualifier | 511           | Build fails if >511 commits since last tag |

## CI/CD Integration

### Build Workflow (`build.yml`)

- Fetches full Git history (`fetch-depth: 0`)
- Fetches all tags (`fetch-tags: true`)
- Version is automatically derived during Gradle configuration

### Release Workflow (`release.yml`)

- Validates that the release tag matches `v*.*.*` pattern
- Builds with the release version (qualifier = 511)

### Pre-release Workflow (`pre-release.yml`)

- Creates pre-releases for main branch builds
- Version name includes dev build info (e.g., `1.2.4-dev.5+abc1234`)
- Pre-release tags use format `pre-release-{short-sha}`

## Implementation

The versioning logic is implemented in:

1. **`app/build.gradle.kts`**: `Versioning` object that resolves version from Git state
2. **`.github/workflows/pre-release.yml`**: Shell script for pre-release version naming

Both implementations follow the same algorithm to ensure consistency.

## Troubleshooting

### "Commits since tag exceeds max qualifier"

Create a new version tag to reset the commit counter:
```bash
git tag v1.2.4
git push origin v1.2.4
```

### "Patch version overflow"

If the base patch is 127 and you need a dev build, create a new minor or major version:
```bash
git tag v1.3.0  # or v2.0.0
git push origin v1.3.0
```

### Version code is unexpectedly low

Ensure the build has access to full Git history and tags:
```bash
git fetch --tags --unshallow
```
