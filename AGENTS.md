# AGENTS.md

Guidance for humans and AI agents contributing to **Letterbox**. This file
follows the open [`AGENTS.md`](https://agents.md) convention so that any
compatible tool — not a single vendor — can discover it.

## Project layout

* `app/` — Android application (Kotlin, Jetpack Compose).
* `rust/letterbox-core/` — Core Rust crate, exposed to Android via UniFFI.
* `rust/letterbox-proxy/` — WARP image-proxy Rust crate (tunnel, provisioning,
  HTTP), exposed via UniFFI.
* `docs/` — Architecture and design notes.
* `.github/workflows/` — CI (build, lint, tests, real WARP smoke test).

## Build & test commands

* Rust tests: `cargo test` in each crate (`rust/letterbox-core`,
  `rust/letterbox-proxy`).
* Rust lint/format: `cargo clippy --all-targets -- -D warnings` and
  `cargo fmt --all -- --check`.
* Android unit tests: `./gradlew test` (depends on `cargoHostBuild`).
* Real Cloudflare WARP end-to-end test (live network, non-blocking in CI):
  `cargo test -p letterbox-proxy --test warp_real -- --ignored --nocapture`.

## Commit message standards

All hand-authored commits MUST follow the
[Conventional Commits](https://www.conventionalcommits.org/) specification. The
full, authoritative rules — schema, approved types, scopes, body/footer rules,
and examples — live in the repository skill:

* [`.github/skills/git-commits/SKILL.md`](.github/skills/git-commits/SKILL.md)

Quick reference:

```text
<type>(<scope>): <subject>     # subject line <= 70 chars, imperative, capitalized, no trailing period

<body>                          # wrap at 72 cols; explain what & why, not how

<footer>                        # e.g. "Resolves #123" or "BREAKING CHANGE: ..."
```

* **Types:** `feat`, `fix`, `refactor`, `docs`, `style`, `perf`, `test`,
  `build`, `ci`, `chore`, `revert`.
* **Common scopes:** `proxy`, `core`, `tunnel`, `warp`, `ui`, `app`, `ffi`,
  `ci`, `build`, `docs`.

A git-native commit template is provided as an optional aid. Enable it per
clone with:

```sh
git config commit.template .gitmessage.txt
```

These standards are **documentation, not a blocking gate**: there is no
commit-lint hook or CI check that rejects non-conforming messages. This is
deliberate, so that automated commits and historical commits are never blocked.

**Exception:** automated dependency-update commits from the Renovate bot use an
`⬆️` emoji prefix (configured in `renovate.json`) and do not follow Conventional
Commits. This is intentional; do not rewrite bot commits.
