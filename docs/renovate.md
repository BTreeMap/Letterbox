# Renovate Dependency Management

This repository uses the [Renovate GitHub App](https://github.com/apps/renovate) to automatically open pull requests for dependency updates.

## What Renovate Updates

| Ecosystem | What's covered | Manager |
|---|---|---|
| **Gradle** | `gradle/libs.versions.toml` version catalog, plugin versions, Gradle wrapper | `gradle`, `gradle-wrapper` |
| **Cargo** | `Cargo.toml` / `Cargo.lock` (workspace and crate-level) | `cargo` |
| **GitHub Actions** | Workflow action versions in `.github/workflows/` | `github-actions` |
| **Docker** | Dockerfile base image tags (if present) | `dockerfile`, `customManagers:dockerfileVersions` |

## Update Policy

### Minor & Patch Updates

- Grouped by manager/datasource to reduce PR noise.
- **Auto-merged** only after all required CI checks pass (Rust tests, Android unit tests, lint).
- No manual review required.

### Major Updates

- A PR is created but **not auto-merged**.
- Requires **explicit approval** via the Dependency Dashboard issue before Renovate opens the PR.
- Labeled with `major` for easy identification.
- Should be reviewed and tested by a maintainer before merging.

## Dependency Dashboard

Renovate creates and maintains a **Dependency Dashboard** issue in the repository. This issue:

- Lists all pending updates and their status.
- Allows maintainers to approve major updates by checking a checkbox.
- Shows any update errors or warnings.

## Required GitHub Settings

For auto-merge to work correctly, the following branch protection settings on `main` are recommended:

### Branch Protection Rules

1. **Require status checks to pass before merging** — Enable this and add the CI checks from the `Build` workflow (e.g., `Lint and Test`) as required status checks.
2. **Require branches to be up to date before merging** — Optional but recommended; Renovate rebases PRs automatically (`rebaseWhen: behind-base-branch`).
3. **Allow auto-merge** — Enable this in the repository settings (`Settings → General → Allow auto-merge`) so that Renovate can merge PRs after CI passes.

### Recommended for Major Updates

- **Require at least 1 approving review** — This prevents accidental merges of major updates. Since major updates have `automerge: false`, this adds an extra layer of safety.

## Renovate GitHub App Permissions

The Renovate GitHub App needs access to this repository. Ensure it is:

- Installed on the repository (or organization-wide with access to this repo).
- Granted permissions to open PRs, create/update issues (for the Dependency Dashboard), and read repository contents.

No Renovate runner workflow is needed — the GitHub App handles scheduling and execution.

## Approving Major Updates

1. Open the **Dependency Dashboard** issue in the repository.
2. Find the major update under the "Awaiting Approval" section.
3. Check the checkbox next to the update.
4. Renovate will create the PR on its next run.
5. Review the PR, ensure CI passes, and merge manually.

## Configuration

The Renovate configuration is in [`renovate.json`](../renovate.json) at the repository root. It extends `config:recommended` and adds project-specific package rules for grouping and auto-merge behavior.
