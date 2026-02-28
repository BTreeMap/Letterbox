# Atlas Journal — Critical Learnings

## 2026-02-27 - Package path mismatch pattern
**Learning:** The Android package is `org.joefang.letterbox` (see `app/build.gradle.kts` applicationId), but the GitHub org is `BTreeMap`. Documentation that references file paths may use the wrong package path (e.g., `com/btreemap/letterbox` instead of `org/joefang/letterbox`). Always verify package paths against the actual `applicationId` in `build.gradle.kts`.
**Action:** When auditing docs that reference Java/Kotlin file paths, cross-check against `applicationId` in `app/build.gradle.kts` and the actual directory structure.

## 2026-02-27 - Cargo workspace has two crates, not one
**Learning:** The Cargo workspace includes both `rust/letterbox-core` and `rust/letterbox-proxy`. Documentation and CI both build/test these independently. Any doc claiming to describe the Rust workspace should mention both crates.
**Action:** When auditing Rust-related docs, verify all workspace members from `Cargo.toml` are mentioned.
