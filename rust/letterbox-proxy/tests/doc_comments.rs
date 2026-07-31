//! Guard against Rust doc comments that cannot survive the trip into Kotlin.
//!
//! UniFFI copies a `///` doc comment verbatim into a Kotlin `/** … */` block.
//! Kotlin, unlike Java, **nests** block comments: a `/*` inside one opens a
//! second comment that needs its own `*/` to close. So a doc containing a media
//! range such as `image` followed by a slash and a star opens a nested comment,
//! the KDoc's own terminator closes only that inner one, and the outer comment
//! runs to end of file — swallowing every declaration after it.
//!
//! That is not a hypothetical. It happened, and the symptom pointed nowhere
//! near the cause: the Kotlin compiler reported one `Unclosed comment` at the
//! last line of the generated bindings plus three dozen "unresolved reference"
//! errors in a *different*, hand-written file, while the Rust that caused it
//! compiled cleanly, passed clippy, and read perfectly well in review.
//!
//! The rule is therefore checked here, in `cargo test`, which runs in an
//! earlier CI job than any Kotlin compilation and names the offending line
//! directly.
//!
//! Scope is the whole `rust/` tree rather than this crate alone: `letterbox-core`
//! generates Kotlin from its own doc comments through the same machinery and has
//! the same exposure, and one guard both crates share cannot drift the way two
//! copies would.

use std::path::{Path, PathBuf};

/// Sequences that begin or end a block comment in the generated Kotlin.
const DELIMITERS: [&str; 2] = ["/*", "*/"];

#[test]
fn doc_comments_carry_no_block_comment_delimiter() {
    let rust_root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("crate lives inside the rust/ workspace")
        .to_path_buf();

    let mut offenders = Vec::new();
    for file in rust_sources(&rust_root) {
        let text = std::fs::read_to_string(&file).unwrap_or_default();
        for (number, line) in text.lines().enumerate() {
            let trimmed = line.trim_start();
            // Only item docs: these are what UniFFI reproduces.
            if !trimmed.starts_with("///") {
                continue;
            }
            if DELIMITERS.iter().any(|d| trimmed.contains(d)) {
                let relative = file.strip_prefix(&rust_root).unwrap_or(&file);
                offenders.push(format!(
                    "{}:{}\n    {trimmed}",
                    relative.display(),
                    number + 1
                ));
            }
        }
    }

    assert!(
        offenders.is_empty(),
        "Doc comments must not contain `/*` or `*/`.\n\n\
         Kotlin block comments nest, and UniFFI copies these docs into one. A \
         media range written literally — `image` then a slash and a star — opens \
         a nested comment that is never closed, and every Kotlin declaration \
         after it disappears.\n\n\
         Reword rather than escape: \"asks only for images\" says the same thing \
         and survives the trip.\n\n{}\n",
        offenders.join("\n")
    );
}

/// Every `.rs` file under `root`, skipping build output.
fn rust_sources(root: &Path) -> Vec<PathBuf> {
    let mut found = Vec::new();
    let mut pending = vec![root.to_path_buf()];

    while let Some(directory) = pending.pop() {
        let Ok(entries) = std::fs::read_dir(&directory) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let name = entry.file_name();
            if path.is_dir() {
                // `target/` holds vendored dependency sources, whose docs are
                // not ours to police and are not generated from.
                if name != "target" && !name.to_string_lossy().starts_with('.') {
                    pending.push(path);
                }
            } else if path.extension().is_some_and(|e| e == "rs") {
                found.push(path);
            }
        }
    }
    found
}
