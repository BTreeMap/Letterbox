# CI/CD Security Improvements

## Overview

This document details the security improvements made to the GitHub Actions workflows for the Letterbox project. The changes follow security best practices and implement the principle of least privilege.

## Problem Statement

The original `ci.yml` workflow had several security concerns:

1. **Global Write Permissions**: All jobs had `contents: write` permission, including those triggered by pull requests
2. **Monolithic Structure**: A single workflow file handled all CI/CD tasks, making it difficult to scope permissions appropriately
3. **Over-privileged PRs**: Pull requests from external contributors had unnecessary write access to the repository
4. **Security Risk**: Malicious PRs could potentially exploit write permissions to modify repository contents or create releases

## Solution

The CI/CD pipeline has been split into three focused workflows, each with minimal, task-appropriate permissions:

### Architecture Comparison

#### Before: Single Monolithic Workflow

```
ci.yml
├── Trigger: push, pull_request, release
├── Permission: contents: write (GLOBAL)
└── Jobs:
    ├── build (inherits write permission)
    ├── pre-release (inherits write permission)
    └── release (inherits write permission)
```

**Security Issues:**
- ❌ PRs have write access
- ❌ All jobs have write access
- ❌ No permission scoping
- ❌ Single point of failure

#### After: Three Focused Workflows

```
pull-request.yml
├── Trigger: pull_request
├── Permission: contents: read (GLOBAL)
└── Jobs:
    ├── lint-and-test (read-only)
    └── build (read-only)

main-branch.yml
├── Trigger: push to main/copilot/**
├── Permission: contents: write (GLOBAL)
└── Jobs:
    ├── lint-and-test (overrides to read)
    ├── build (overrides to read)
    └── pre-release (uses write)

release.yml
├── Trigger: release published
├── Permission: contents: write (GLOBAL)
└── Jobs:
    ├── build (overrides to read)
    └── publish (uses write)
```

**Security Improvements:**
- ✅ PRs have read-only access
- ✅ Jobs override to read where possible
- ✅ Minimal permission scoping
- ✅ Isolated workflows

## Detailed Security Model

### 1. Pull Request Workflow (`pull-request.yml`)

**Trigger:** Pull requests targeting `main` branch

**Global Permission:** `contents: read`

**Rationale:** Pull requests, especially from external contributors, should never have write access to the repository. This prevents:
- Malicious PRs from modifying code
- Unauthorized release creation
- Repository content tampering

**Jobs:**
| Job | Permission | Purpose |
|-----|------------|---------|
| lint-and-test | read (inherited) | Run code quality checks and tests |
| build | read (inherited) | Verify the code builds successfully |

**Artifact Policy:** 7-day retention (short-lived for verification only)

### 2. Main Branch Workflow (`main-branch.yml`)

**Trigger:** Pushes to `main` or `copilot/**` branches

**Global Permission:** `contents: write` (but scoped down per job)

**Rationale:** Main branch builds need to create pre-releases, but not all jobs require write access. We use job-level permission overrides to minimize exposure.

**Jobs:**
| Job | Permission | Purpose |
|-----|------------|---------|
| lint-and-test | read (overridden) | Run code quality checks and tests |
| build | read (overridden) | Build debug and release APKs |
| pre-release | write (inherited) | Create automated pre-releases for main branch only |

**Artifact Policy:** 30-day retention (for ongoing testing)

**Security Notes:**
- Only the `pre-release` job actually uses write permissions
- Pre-releases are conditional: only created for `refs/heads/main`
- Copilot branches are tested but don't create pre-releases

### 3. Release Workflow (`release.yml`)

**Trigger:** Release publication events

**Global Permission:** `contents: write` (but scoped down per job)

**Rationale:** Official releases need to upload APK assets, but the build process itself doesn't require write access.

**Jobs:**
| Job | Permission | Purpose |
|-----|------------|---------|
| build | read (overridden) | Build the final release APK |
| publish | write (inherited) | Upload APK to the GitHub release |

**Artifact Policy:** 90-day retention (long-term availability)

**Security Notes:**
- Only the `publish` job actually uses write permissions
- Build artifacts are created with read-only access before being uploaded

## Security Benefits

### 1. Least Privilege Principle

Each workflow and job has the minimum permissions required for its specific function:

- **Read-Only Default**: Most operations (lint, test, build) only need read access
- **Write When Necessary**: Only release/pre-release operations need write access
- **Job-Level Scoping**: Even within workflows with write permission, individual jobs override to read where possible

### 2. Attack Surface Reduction

**Before:**
- Any compromised PR build could modify repository contents
- 100% of jobs had write access

**After:**
- PR builds are completely isolated with read-only access
- Only ~20% of jobs have write access (pre-release and publish jobs)

### 3. Audit Trail Clarity

With separated workflows:
- Easy to identify which workflows can modify the repository
- Clear distinction between validation (read) and publication (write) operations
- Simplified security reviews and compliance audits

### 4. Defense in Depth

Multiple layers of security controls:

1. **Workflow-Level**: Different workflows for different trust levels (PR vs. main vs. release)
2. **Job-Level**: Permission overrides within workflows
3. **Conditional Logic**: Pre-releases only on main branch
4. **Trigger-Level**: Release workflow only runs on release events

## Comparison Table

| Aspect | Before (ci.yml) | After (split workflows) |
|--------|-----------------|-------------------------|
| **PR Write Access** | ❌ Yes (security risk) | ✅ No (read-only) |
| **Permission Scoping** | ❌ Global for all | ✅ Per-workflow + per-job |
| **Workflows with Write** | 1 (100%) | 2 (66%), but most jobs read-only |
| **Jobs with Write** | 3 of 3 (100%) | 2 of 7 (29%) |
| **Separation of Concerns** | ❌ Monolithic | ✅ Focused workflows |
| **Audit Complexity** | ❌ High (all mixed) | ✅ Low (clear separation) |
| **Attack Surface** | ❌ Large | ✅ Minimal |
| **Best Practice Compliance** | ❌ No | ✅ Yes |

## Best Practices Implemented

1. ✅ **No Write Permissions for PRs**: Pull requests cannot modify repository contents
2. ✅ **Job-Level Permission Overrides**: Granular control within workflows
3. ✅ **Conditional Execution**: Pre-releases only on main branch
4. ✅ **Clear Separation**: Different workflows for different purposes
5. ✅ **Documentation**: Comprehensive comments explaining permission choices
6. ✅ **Artifact Retention Policies**: Appropriate retention periods per workflow type

## Migration Notes

### Breaking Changes
None. The functionality remains identical; only the security model has been improved.

### Workflow Name Changes
- Old: `CI` (single workflow)
- New: `Pull Request Checks`, `Main Branch Build`, `Release Publication`

### Artifact Names
- PR artifacts now include PR number: `letterbox-debug-apk-pr-{number}`
- Main branch and release artifacts retain original names

## Testing and Validation

To validate these changes:

1. **Pull Request Testing**: Open a PR and verify:
   - ✅ Workflow runs with read-only permissions
   - ✅ Lint and tests execute successfully
   - ✅ Debug APK is built and uploaded
   - ✅ No write operations occur

2. **Main Branch Testing**: Push to main and verify:
   - ✅ All jobs complete successfully
   - ✅ Pre-release is created with APK attached
   - ✅ Job-level permission overrides work correctly

3. **Release Testing**: Publish a release and verify:
   - ✅ Build job creates release APK
   - ✅ Publish job uploads APK to release
   - ✅ Permission scoping is correct

## References

- [GitHub Actions: Security hardening](https://docs.github.com/en/actions/security-guides/security-hardening-for-github-actions)
- [GitHub Actions: Permissions](https://docs.github.com/en/actions/using-jobs/assigning-permissions-to-jobs)
- [OWASP: Least Privilege Principle](https://owasp.org/www-community/Access_Control)

## Conclusion

These changes significantly improve the security posture of the CI/CD pipeline by:
- Eliminating unnecessary write permissions for pull requests
- Implementing granular permission scoping
- Following industry best practices for CI/CD security
- Maintaining full functionality while reducing attack surface

The new architecture is more maintainable, auditable, and secure.
