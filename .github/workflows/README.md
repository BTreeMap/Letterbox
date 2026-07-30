# GitHub Actions Workflows

## The idea this pipeline is organised around

Every build output is a pure function of its inputs. Naming those inputs
decides how many times the pipeline has to compute it.

```
rust output = f(source, crate set, cargo profile, target triple)
apk         = g(source, variant, CI_BUILD_TYPE) + the .so files
```

There are exactly **three Rust build identities** in this repository:

| Identity | Profile | Target | Who needs it |
| --- | --- | --- | --- |
| `host-dev` | dev | `x86_64-unknown-linux-gnu` | `cargo fmt`/`clippy`/`test`, `uniffi-bindgen`, the MASQUE smoke test |
| `host-release` | release | `x86_64-unknown-linux-gnu` | Gradle unit tests — JNA loads `target/release/*.so` |
| `android-release` | release | `aarch64`/`armv7`/`x86_64-linux-android` | the `.so` files inside every APK |

Each is produced by exactly one job, and **the cache key is the identity** —
not the job name, not the crate. A job that needs an identity another job
already produced restores it. Two jobs that want the same identity must spell
it the same way, or the memoisation silently misses.

Note what is **not** an argument to `f`: `CI_BUILD_TYPE`. The native libraries
for a release tag are byte-identical to those for the commit the tag points at.
Only the APK differs, because `versionName` and `versionCode` are derived from
the build type. That single observation is why `release.yml` no longer contains
a build: it calls `build.yml` to reassemble the APK with release stamping, and
the cross-compile behind it is a cache hit.

The Android cross-compile and the Gradle assembly are **separate jobs** joined
by the `letterbox-jnilibs` artifact. That split is what lets the assemble job
run with no Rust toolchain, no NDK and no `cargo-ndk`, and lets the UI-test
workflow consume the same libraries without rebuilding them.

## Workflows

### `build.yml` — Build

**Trigger:** push to `main`/`copilot/**`, pull requests to `main`, or
`workflow_call`. **Not tags** — see `release.yml`.

**Permissions:** `permissions: {}` at the workflow level; every job takes
`contents: read`.

| Job | Identity | What it does | Toolchain it needs |
| --- | --- | --- | --- |
| `rust` | `host-dev` | `cargo fmt`, `cargo clippy -D warnings`, `cargo test --workspace` | Rust only |
| `native-libs` | `android-release` | one `cargo ndk` pass for all three ABIs → `letterbox-jnilibs` | Rust + NDK |
| `android-checks` | `host-release` | `./gradlew lint testProdDebugUnitTest` | JDK + SDK + Rust (host) |
| `apks` | — | downloads `letterbox-jnilibs`, `assembleStagingDebug assembleProdRelease` | JDK + SDK, **no Rust** |
| `warp-smoke-test` | `host-dev` | live Cloudflare MASQUE end-to-end, non-blocking | Rust only |

Only `apks` has a `needs:`, and only on `native-libs`. Assembly does not depend
on lint or on the test suites, so gating it behind them would put their runtime
on the critical path of every build for no added signal: each job reports its
own failure, and everything downstream keys off the workflow's conclusion.

`CI_BUILD_TYPE` is set on the `apks` job alone — it is precisely the parameter
that distinguishes otherwise identical APKs, and nothing else consumes it.

### `release.yml` — Release Publication

**Trigger:** a release is published. **Environment:** `ci:release`.

```
validate  (tag matches v*.*.*, no checkout, no token)
   ↓
build     uses: ./.github/workflows/build.yml   with build_type: release
   ↓
publish   contents: write — signs and uploads
```

`build.yml` no longer triggers on tags, so a release-stamped APK is assembled
exactly once, here. The `publish` job holds the production keystore and runs no
project build code: it checks out only `.github/actions` and nothing else.

It also asserts that the APK's own `versionName` equals the release tag. If
they disagree, the release was published from a commit the tag does not point
at, and the asset would claim a version it is not.

### `pre-release.yml` — Pre-release

**Trigger:** completion of Build on `main`. **Environment:** `ci:release`.
**Permissions:** `contents: write`, `actions: read`.

Signs the main-branch APK and publishes it as a pre-release. The tag, the title
and the asset name are all read back out of the signed APK — see
[Release naming](#release-naming).

It checks out **only** `.github/actions`, never the built commit: this job holds
the production keystore, and it used to re-derive the version in shell from a
full history checkout, which was a second implementation of
`Versioning.resolveFromGit` that could disagree with the one that stamped the
APK.

### `sign-test.yml` — Sign Test APK

**Trigger:** completion of Build for a pull request. **Environment:** `ci:test`.

The most dangerous job in the repository: it runs in the privileged
`workflow_run` context — the base repository's secrets, not the fork's — and its
input is an APK built from unreviewed code. See
[Signing keys](#signing-keys-the-trust-boundary).

### `android-ui.yml` — Android UI Tests

**Trigger:** completion of Build on `main`/`copilot/**`.
**Permissions:** `contents: read`, `actions: read`.

Instrumented tests on a Gradle Managed Device (Pixel 7, API 34). Downloads
`letterbox-jnilibs` rather than repeating the Android cross-compile. Its Gradle
cache is **read-only unconditionally** — this workflow runs in the default
branch's context (`github.ref` is `refs/heads/main`) but checks out the
triggering run's head, which may be a `copilot/**` branch, so a ref check would
call it trusted when it is not.

### `export-generated-sources.yml` — Export Generated Sources

**Trigger:** push to `main` (ignoring every generated path, `docs/**` and
Markdown), plus `workflow_dispatch`. **Permissions:** `contents: write` — the
only workflow in this repository that can write to the default branch.

Regenerates the two artefacts derived from sources here that must nevertheless
be committed:

| Path | Generated by | Why it must be committed |
| --- | --- | --- |
| `app/schemas/**` | Room's annotation processor (`:app:kspProdDebugKotlin`) | `MigrationTestHelper` creates a database at a starting version by executing the SQL from that version's exported JSON, so a migration from N to N+1 can only be tested if the JSON for **N** is already committed. |
| `app/src/main/java/org/joefang/letterbox/ffi/**.kt` | `uniffi-bindgen`, from the compiled Rust libraries | Kotlin sources are compiled against them, so they cannot be produced during the build that consumes them. |

**Why the bindings belong here:** UniFFI embeds a checksum per exported item in
the generated Kotlin and asserts it against the loaded library on the first FFI
call. Those checksums cover docstrings, so **editing a doc comment on an
exported Rust function is enough to break the app at runtime** — with no compile
error and no obvious cause.

**Why two jobs:** `generate` builds with `contents: read` and
`persist-credentials: false` and hands its output over as an artefact; `commit`
holds the writable token and runs nothing but `git`. Gradle plugins, KSP
processors, `build.rs` scripts and proc macros are all third-party code
executing by design during a build, so a compromised build dependency can
corrupt the *content* of a generated file — which review catches — but cannot
reach a token that writes to `main`.

**Loop prevention:** three independent guards. (1) The push uses the automatic
`GITHUB_TOKEN`, and GitHub raises no workflow events for it — the load-bearing
guard. (2) `paths-ignore` excludes every generated path. (3) The commit step is
skipped when the output already matches what is committed.

Its Rust cache names `host-dev` and is **restore-only**: one identity, one
producer.

## Dependency graph

```
Push to main / copilot/**            Pull request
         │                                │
         └────────────┬───────────────────┘
                      ▼
             ┌──────────────────┐
             │     build.yml    │
             │                  │
             │  rust ─┐         │   host-dev
             │        │         │
             │  native-libs ────┼──► letterbox-jnilibs
             │        │         │   android-release
             │  android-checks  │   host-release
             │        │         │
             │  apks ◄┘         │   ← jnilibs, no Rust toolchain
             │        │         │
             │  warp-smoke-test │   host-dev (non-blocking)
             └────────┬─────────┘
                      │ workflow_run
          ┌───────────┼───────────┐
          ▼           ▼           ▼
   android-ui.yml  sign-test   pre-release
   (main,copilot)  (PRs only)  (main only)
                   ci:test     ci:release


Release published                Push to main
         ▼                            ▼
  ┌─────────────┐          ┌────────────────────────────┐
  │ release.yml │          │ export-generated-sources   │
  │  validate   │          │   generate  contents: read │
  │     ▼       │          │      │ artefact            │
  │  build ─────┼─ calls   │      ▼                     │
  │     ▼       │  build.yml   commit  contents: write  │
  │  publish    │          │      git only              │
  └─────────────┘          └────────────────────────────┘
   ci:release
```

## Release naming

Identity and addressability are different jobs, and giving both to the filename
is what made the old scheme unusable from either side. They are now split:

| | Carries | Value |
| --- | --- | --- |
| **Tag** | which build this is | `v1.2.3`, `v0.1.1-dev.7+abc1234` |
| **Asset** | a name a script can write down | `letterbox.apk`, always |
| **Sidecar** | the version, for machines | `letterbox.json` |

| Build | Tag | Assets |
| --- | --- | --- |
| Release | `v1.2.3` | `letterbox.apk`, `letterbox.json` |
| Pre-release | `v0.1.1-dev.7+abc1234` | `letterbox.apk`, `letterbox.json` |
| PR test build | *(not released)* | `letterbox-test.apk`, `letterbox-test.json` |

So this works, for every stable release, with no knowledge of the version:

```bash
curl -LO https://github.com/BTreeMap/Letterbox/releases/latest/download/letterbox.apk
curl -Ls https://github.com/BTreeMap/Letterbox/releases/latest/download/letterbox.json | jq -r .version
```

and pre-releases, which `/releases/latest/` never resolves to, take one API call:

```bash
gh release download -R BTreeMap/Letterbox --pattern 'letterbox.*'
```

`letterbox.json` is written from the APK by the signing action:

```json
{
  "version": "v0.1.1-dev.7+abc1234",
  "versionCode": 8389127,
  "package": "org.joefang.letterbox",
  "asset": "letterbox.apk",
  "size": 27182818,
  "sha256": "…",
  "certSha256": "…"
}
```

The digest and certificate are there because this app is sideloaded: without
them a download can only be trusted as far as the transport. The test build's
`package` ends `.test`, which is how the staging flavour installs alongside the
real app.

Three things changed and each had a reason:

1. **The tag was `pre-release-<short sha>`.** GitHub paginates releases roughly
   by time and then orders within the page by tag name, so a tag whose leading
   characters are a constant followed by a hash sorts by nothing at all — the
   list arrives shuffled. A tag that begins with the version sorts by version.
   (`dev.N` is not zero-padded, so ordering within one patch series is still
   lexical rather than numeric. That follows from `versionName`'s documented
   format, which these workflows report rather than define.)

2. **The asset was `signed-app-prod-release-unsigned.apk`** — the signing step
   prefixed `signed-` onto AGP's output filename, which ends in `-unsigned`. It
   is now a constant. A version-bearing filename reads well in a Downloads
   folder but cannot be addressed by a URL, and the release page states the
   version directly above the asset anyway.

3. **The title was `Pre-release <version>`.** GitHub truncates it in the sidebar
   at roughly the width of `Pre-release v0.0.7-dev…`, so the prefix spent the
   entire visible budget restating the badge shown next to it. The title is now
   the bare version.

`target_commitish` is also set on pre-releases. Without it the tag is created at
whatever the default branch happens to be when the release is made, which is not
necessarily the commit that was built — the tag and the body then name different
commits.

## Signing keys: the trust boundary

`secrets.ANDROID_KEYSTORE_B64` resolves against the job's `environment` first
and the repository second. **If an environment does not define it, GitHub falls
back to the repository secret silently.** So a `ci:test` job meant to sign
untrusted pull-request code with a throwaway key will sign it with the
production key the moment that environment secret is missing, renamed or
deleted — and nothing downstream can tell, because a correctly signed APK looks
the same either way. The result is an APK that Android accepts as an update to
the real app, and it is reachable by a configuration mistake rather than an
exploit.

`.github/actions/sign-apk` therefore does not trust the key, it **checks** it:
after signing, the certificate is read back out of the APK and compared against
the fingerprint the calling environment declares in
`vars.ANDROID_SIGNING_CERT_SHA256`. A mismatch fails the job and destroys the
artefact. The input is required precisely so that "nobody configured it" cannot
degrade into "unchecked".

> **Setup required.** Each environment (`ci:test`, `ci:release`) must define the
> variable `ANDROID_SIGNING_CERT_SHA256`, holding the SHA-256 digest of the
> certificate that environment's keystore presents. Obtain it with:
>
> ```bash
> apksigner verify --print-certs <a previously signed apk>
> # Signer #1 certificate SHA-256 digest: <this value>
> ```

Two smaller hardenings came with the consolidation of three copies of the
signing script into one action:

- Passwords reach `apksigner` through `pass:env:` rather than
  `--ks-pass "pass:…"`, which put them in the process command line where
  anything on the runner could read them out of `/proc`.
- The keystore is removed by an `EXIT` trap. The previous form deleted it on the
  last line of the script, which `set -e` skips whenever `apksigner` fails —
  leaving the key on a runner that later steps still use.

Neither signing job checks out the code it is signing. They take only
`.github/actions`, by sparse checkout, from a trusted ref.

## Cache policy

Restoring is unrestricted; GitHub already scopes reads to the current branch and
the default branch. **Writing** is limited to trusted refs, because a pull
request runs untrusted code and must not be able to place an object that a later
release build links against:

```yaml
CACHE_WRITE: ${{ github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/v') }}
```

Derived from the ref rather than the event: pushing a tag or to `main` requires
write access and is therefore already trusted, while a pull request's ref is
`refs/pull/N/merge` and a feature branch is neither. It gates both
`Swatinem/rust-cache`'s `save-if` and `setup-gradle`'s `cache-read-only`.

`android-ui.yml` is the exception and is hardcoded read-only: it runs in the
default branch's context but checks out someone else's head.

The Gradle **build cache** is enabled (`org.gradle.caching=true`). Without it
`setup-gradle` cached downloaded dependencies but no compiled output, so the
UI-test job recompiled the whole app the assemble job had just compiled. The
configuration cache is deliberately **not** enabled: the version is resolved by
shelling out to git during configuration, which is exactly the kind of work the
configuration cache would freeze and replay with a stale answer.

## Permissions

1. **Default deny.** Every workflow declares `permissions: {}` at the top level
   and every job grants itself the minimum it needs, so a job added later starts
   with no scopes rather than inheriting whatever the workflow declared.
2. **Credentials are not persisted** into the work tree of any job that runs
   project code. `actions/checkout` writes the token into `.git/config` by
   default and leaves it there for the rest of the job.
3. **Write access to `main` is isolated to one workflow.**
   `export-generated-sources.yml` triggers only on `push` to `main` — never on
   `pull_request`, never on `workflow_run` — so an untrusted pull request cannot
   reach it, run with its permissions, or influence what it commits.
4. **The push-capable token never coexists with project build code**, and the
   jobs that hold signing keys check out no code at all beyond
   `.github/actions`.

## Runners and the native-library constraint

All jobs run on `ubuntu-24.04` (amd64). The Android cross-compile is **pinned to
amd64 on purpose**: the NDK ships no `linux-aarch64` host toolchain. Google's
SDK index has never advertised a `('linux', 'aarch64')` archive for any NDK from
r16 to r30 — the only aarch64 *host* it offers is macOS — and the prebuilt path
inside the Linux tarball is `toolchains/llvm/prebuilt/linux-x86_64` with no
sibling. An arm64 runner would have to run that clang under emulation.

It would not be faster even if it existed. The work is compiling Rust and
BoringSSL *for Android targets*; the host architecture decides which binary does
the compiling, not how much compiling there is, and GitHub's public arm64
runners have the same core count as the x86_64 ones.

The NDK is pinned to `27.3.13750724`, which is the version the `ubuntu-24.04`
image already carries, so `sdkmanager --install` is a no-op instead of a ~1 GB
download on every run. It stays an explicit version rather than "whatever the
image has": if the image moves, this fetches r27.3 and the build stays
reproducible, only slower for that one run.

Both `ANDROID_NDK_HOME` and `ANDROID_NDK_ROOT` are exported to it. cargo-ndk
reads the first and cmake's Android toolchain file reads the second, so setting
only one leaves the two halves of this build free to resolve different NDKs —
and the half reading `ANDROID_NDK_ROOT` is the one that compiles quiche's
vendored BoringSSL. Under r26 the image's own `ANDROID_NDK_ROOT` pointed at
r27.3 while we pinned r26, and cargo-ndk warned about exactly that.

`cargo ndk … build --release --workspace --lib` — `--lib` is load-bearing.
Without it the pass also cross-compiled the two `uniffi-bindgen` *binaries* for
every ABI: six links of a host-only developer tool that never enters an APK.

No checkout requests submodules. The repository's only submodule is
`.github/skills` (agent skill documentation); no build reads it.

## Action pinning policy: latest major tag

Every `uses:` names the action's **current major-version tag**:

| Action | Pinned to | Upstream latest |
| --- | --- | --- |
| `actions/checkout` | `@v7` | v7.0.1 |
| `actions/setup-java` | `@v5` | v5.6.0 |
| `actions/upload-artifact` | `@v7` | v7.0.1 |
| `actions/download-artifact` | `@v8` | v8.0.1 |
| `android-actions/setup-android` | `@v4` | v4.0.1 |
| `gradle/actions/setup-gradle` | `@v6` | v6.2.0 |
| `Swatinem/rust-cache` | `@v2` | v2.9.1 |
| `softprops/action-gh-release` | `@v3` | v3.0.2 |
| `dtolnay/rust-toolchain` | `@stable` | see below |

This is a deliberate trade. Pinning to a commit SHA removes the publisher's
ability to substitute code, but every action then needs a manual bump to receive
a fix, and pins that nobody updates end up worse than a moving tag: they
silently retain known-vulnerable versions. Tracking the major tag means security
fixes and runtime-deprecation updates arrive without intervention, at the cost
of trusting the publisher not to move the tag maliciously. For the accounts
above — GitHub's own org, Gradle's, and three widely-used community actions —
that trust is already implied by using them at all.

Two rules make the trade smaller:

1. **Follow the channel the action actually maintains, never a patch tag.** A
   patch pin such as `@v6.2.0` gets the mutability without the updates, which is
   the worst of both.
2. **Least privilege is what actually bounds the damage.** No job holds more
   than it needs, and the jobs that can push to `main` or reach a signing key
   run no third-party action beyond `checkout` and `download-artifact`.

When a major version is superseded, bump it here and update the table.

### Exception: `dtolnay/rust-toolchain@stable`

This one is pinned to a **branch**, which the rule above would normally forbid.
It is correct here, and the reasoning generalises: *find the ref the maintainer
actually keeps current*, rather than assuming it is the one shaped like a
version.

That action's ref namespace does not describe the action's own versions — it
describes **Rust toolchain versions**. Its branches are `1.0`, `1.1`, … `1.14`,
alongside the channel branches `stable`, `beta` and `nightly`; `@1.14` means
"install Rust 1.14". A ref named `v1` therefore reads as a Rust version in this
namespace, not an action version.

It is also stale. `v1` is the repository's only release, its tag commit dates
from 2025-08 and its release metadata is backdated to 2022, whereas the `stable`
branch tracks the action's development and moved as recently as 2026-07.
Pinning `@v1` would buy the ambiguity *and* the frozen-pin failure mode this
policy exists to avoid.

`@stable` additionally defaults the `toolchain` input, which `v1` marks
required — so the branch is the documented usage, not merely the current one.

## Artifact retention

| Artifact | Days |
| --- | --- |
| `letterbox-jnilibs` | 7 |
| `android-check-reports` | 7 |
| Unsigned APKs | 30 |
| `letterbox-staging-signed-apk` | 14 |
| `android-ui-test-artifacts` | 14 |
| `generated-sources` | 1 |

## Testing locally

[`act`](https://github.com/nektos/act) can run individual workflows:

```bash
act push -W .github/workflows/build.yml
```

It requires Docker, does not perfectly replicate the runner image, and cannot
run the UI tests (they need KVM). The Rust jobs are reproducible directly:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```
