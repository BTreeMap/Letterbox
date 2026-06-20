# AGENTS.md

Letterbox is a privacy-focused Android app (Kotlin / Jetpack Compose) backed by
two Rust crates exposed over UniFFI. Treat this repo as a monorepo: one Gradle
module plus a Cargo workspace. Vendor-neutral by design (the open
[`AGENTS.md`](https://agents.md) convention). Nested `AGENTS.md` files in `app/`,
`rust/letterbox-core/`, and `rust/letterbox-proxy/` add module-local rules — the
file closest to your working directory wins.

## Tooling & Commands

Use `cargo` for Rust and the Gradle wrapper (`./gradlew`) for Android. Never use
a system-wide `gradle`, and never hand-edit generated artifacts.

| Task | Command |
|------|---------|
| Core Rust tests | `cargo test` in `rust/letterbox-core` |
| Proxy Rust tests | `cargo test` in `rust/letterbox-proxy` |
| Rust lint | `cargo clippy --all-targets -- -D warnings` |
| Rust format | `cargo fmt --all` (verify with `--all -- --check`) |
| Android unit tests | `./gradlew test --no-daemon` (auto-builds host Rust libs) |
| Android lint | `./gradlew lint --no-daemon` |
| Assemble APK | `./gradlew :app:assembleProdDebug` (`-PrustBuild=true` for device libs) |
| Live WARP smoke test | `cargo test -p letterbox-proxy --test warp_real -- --ignored --nocapture` |

`./gradlew test` triggers `cargoHostBuild`, so you never need to build the Rust
libraries by hand before running Android tests.

## Boundaries & Constraints

* Never commit secrets, signing keystores, or WARP credentials. Keystore and
  release wiring uses documented Gradle properties / env vars — see
  `docs/signing.md`.
* Do not hand-edit UniFFI-generated bindings under
  `app/src/main/java/org/joefang/letterbox/ffi/`. Change the Rust `#[uniffi]`
  surface in the relevant crate and regenerate instead.
* Do not loosen lints to make code compile. `clippy` runs with `-D warnings`;
  fix the root cause rather than adding `#[allow(...)]`.
* Do not persist blobs with direct file writes. Use `HistoryRepository`, the
  content-addressable store (`docs/deduplication.md`).
* Do not run search/sort ad hoc. Go through the Room FTS4 layer
  (`docs/full-text-search.md`).
* Do not push tags or trigger releases unless explicitly asked — the release
  flow lives in `.github/workflows/`.
* Do not rewrite Renovate bot commits (the `⬆️`-prefixed ones); they
  intentionally bypass the commit conventions below.

## Engineering Standards (mandatory, every session)

Enforced on all hand-authored Rust. Full rationale, examples, and the autonomous
execution contract: `docs/agents/engineering-standards.md`.

* **Make invalid states unrepresentable.** Encode invariants in the type system
  so invalid states fail at compile time, not at runtime.
* **Idiomatic error handling.** Return `Result` / `Option`; never swallow errors
  or `unwrap()` on fallible paths outside tests.
* **Zero-cost abstractions.** Prefer generics + traits + monomorphization over
  `Box<dyn>`; reserve dynamic dispatch for genuine runtime polymorphism.
* **No appeasement clones.** Do not reach for `.clone()`, `Rc`, or `Arc` just to
  satisfy the borrow checker. Redesign ownership and data flow instead.
* **Ruthless refactoring.** Prune dead code and rewrite unsound interfaces; the
  app ships from `main` with no external API-stability obligation.
* **500-line file limit.** Split any source file approaching 500 lines into
  cohesive submodules.

## Agent Operating Protocol

* Operate autonomously. Resolve ambiguity by making the most reasonable
  technical assumption, documenting it, and proceeding — do not stall for
  permission on routine, reversible work.
* Maintain an explicit, continuously updated task list for multi-step work.
* Delegate discrete, context-heavy subtasks to keep the working context focused.
* On verifiable completion, emit an explicit final status message and halt;
  never stop silently mid-task.

## Commit Standards

Hand-authored commits follow [Conventional Commits](https://www.conventionalcommits.org/).
Authoritative schema, approved types, and scopes live in the repository skill:
[`.github/skills/git-commits/SKILL.md`](.github/skills/git-commits/SKILL.md).

```text
<type>(<scope>): <subject>     # <= 70 chars, imperative, capitalized, no trailing period
```

These standards are documentation, not a blocking gate (no commit-lint hook), so
automated and historical commits are never blocked. Enable the optional template
per clone with `git config commit.template .gitmessage.txt`.

## Domain Documentation (load just-in-time)

Read the matching file only when your task touches that domain:

* Overall architecture & data flow → `docs/architecture.md`
* Engineering standards (full) & execution contract → `docs/agents/engineering-standards.md`
* Image proxy / WARP / WireGuard → `docs/image-proxy-design.md`, `docs/remote-images.md`
* History dedup & content-addressable store → `docs/deduplication.md`
* Full-text search (FTS4) → `docs/full-text-search.md`
* Signing & versioning → `docs/signing.md`, `docs/versioning.md`
* Dependency policy (Renovate) → `docs/renovate.md`
* Build / test troubleshooting → `docs/troubleshooting.md`
