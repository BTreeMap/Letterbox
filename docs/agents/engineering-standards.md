# Engineering Standards & Execution Contract

The non-negotiable summary lives in the root [`AGENTS.md`](../../AGENTS.md) and is
in force for **every** coding session. This document is the full rationale and
the autonomous execution contract. Load it when you are writing or refactoring
Rust, or when you need the detailed operating protocol.

Scope note: the language-specific standards below apply to the repository's Rust
(`rust/letterbox-core`, `rust/letterbox-proxy`). The Kotlin/Compose app follows
the universal directives in spirit (invalid states unrepresentable, explicit
error handling, small cohesive files) but uses idiomatic Kotlin patterns, not
Rust ones.

## 1. Core Operating Rules

1. **Autonomy.** Operate without human intervention on routine, reversible work.
   Do not ask permission to proceed or for general opinions. When faced with
   ambiguity, make the most reasonable technical assumption, document it, and
   proceed.
2. **State management.** Maintain and continuously update an explicit task list
   for multi-step work, tracking pending, active, and completed steps so you do
   not lose your place.
3. **Context optimization.** Monitor task scope. When a subtask is deep or risks
   overwhelming the working context, delegate it so the main context stays
   focused.
4. **Termination protocol.** Never stop silently. On verifiable completion of the
   objective, emit a clear final status message and halt to await the next
   directive.

These rules are bounded by repo-level safety: do not push tags, trigger
releases, force-push, or touch secrets/keystores/WARP credentials without an
explicit request (see root `AGENTS.md` › Boundaries).

## 2. Universal Directives

* **Make invalid states unrepresentable.** Exhaustively leverage the type system
  to prevent invalid states at compile time rather than guarding at runtime.
* **Ruthless refactoring.** The app ships from `main` with no external
  API-stability obligation. If an interface is outdated, poorly named, or
  unsound, rewrite it and delete the legacy code. Actively prune dead code.
* **Aggressive modularization (500-line limit).** No single source file may
  exceed ~500 lines. Segment, extract, and refactor any file approaching the
  limit into smaller, cohesive submodules.
* **Idiomatic error handling.** Never swallow errors. Use explicit return types
  (`Result` / `Option` in Rust). Reserve `unwrap()` / `expect()` for tests or
  provably-infallible paths.

## 3. Rust-Specific Execution

* **Strict ownership.** Design the architecture *for* the borrow checker.
  Pre-calculate lifetimes and ownership hierarchies.
* **No appeasement allocations.** Do not reach for `.clone()`, `Rc`, `Arc`, or
  unnecessary `Copy` bounds simply to satisfy the compiler. If lifetimes clash,
  redesign the data flow.
* **Zero-cost abstractions.** Prefer traits, generics, and monomorphization over
  dynamic dispatch (`Box<dyn _>`) unless runtime polymorphism is genuinely
  required.
* **Concurrency.** Favor message passing over shared mutable state (`Mutex`).
* **Lints are law.** `cargo clippy --all-targets -- -D warnings` must pass. Fix
  the root cause; do not silence with `#[allow(...)]`.

## 4. Output Contract & Execution Loop

For multi-step work, keep the loop legible. At each meaningful step, make clear:

* **Current state** — what was just completed.
* **Assumptions made** — any technical decisions taken independently.
* **Task-list update** — items added or checked off.
* **Architectural plan** (only when writing/modifying code):
  1. *Resource / ownership strategy* — Rust lifetimes and borrowing for the
     change.
  2. *Type-state / PLT plan* — how the type system rules out invalid states for
     this feature.
  3. *Pruning targets* — which legacy code is being deleted, or which oversized
     file is being split to meet the 500-line limit.
* **Next action** — the concrete command, edit, or delegated subtask being run.

When not writing code, the architectural plan is `N/A`.
