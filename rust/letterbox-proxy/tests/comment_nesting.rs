//! Kotlin nests block comments; Rust and Java do not.
//!
//! So a media range in prose — `image` then a slash and a star — opens a comment
//! the enclosing terminator only half closes, and the rest of the file is
//! swallowed. This cost two CI rounds: once via a Rust doc UniFFI copied into
//! the bindings, once via a hand-written Kotlin doc. Both times the reported
//! errors were in a different file from the cause.

use std::path::{Path, PathBuf};

const DELIMITERS: [&str; 2] = ["/*", "*/"];

fn repository_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .ancestors()
        .nth(2)
        .expect("crate lives at <repo>/rust/letterbox-proxy")
        .to_path_buf()
}

/// Forbid delimiters in Rust item docs, which UniFFI copies verbatim.
///
/// Stricter than balance: a balanced pair would survive the trip but comment out
/// whatever sat between its halves. Covers all of `rust/`, since letterbox-core
/// generates Kotlin through the same machinery.
#[test]
fn rust_doc_comments_carry_no_block_comment_delimiter() {
    let root = repository_root().join("rust");
    let mut offenders = Vec::new();

    for file in sources_with_extension(&root, "rs") {
        let text = std::fs::read_to_string(&file).unwrap_or_default();
        for (number, line) in text.lines().enumerate() {
            let trimmed = line.trim_start();
            if !trimmed.starts_with("///") {
                continue;
            }
            if DELIMITERS.iter().any(|d| trimmed.contains(d)) {
                let shown = file.strip_prefix(&root).unwrap_or(&file);
                offenders.push(format!("{}:{}\n    {trimmed}", shown.display(), number + 1));
            }
        }
    }

    assert!(
        offenders.is_empty(),
        "Rust docs must not contain a block-comment delimiter; UniFFI copies \
         them into Kotlin comments, which nest. Reword rather than escape.\n\n{}\n",
        offenders.join("\n")
    );
}

/// Every Kotlin source, generated or hand-written, must close what it opens.
#[test]
fn kotlin_block_comments_are_balanced() {
    let root = repository_root().join("app/src");
    let mut offenders = Vec::new();

    for file in sources_with_extension(&root, "kt") {
        let text = std::fs::read_to_string(&file).unwrap_or_default();
        if let Err(reason) = scan_comments(&text) {
            let shown = file.strip_prefix(&root).unwrap_or(&file);
            offenders.push(format!("{}: {reason}", shown.display()));
        }
    }

    assert!(
        offenders.is_empty(),
        "Kotlin block comments must balance.\n\n{}\n",
        offenders.join("\n")
    );
}

/// Walk `source` as Kotlin, reporting the first unbalanced block comment.
///
/// Literals are skipped, not scanned: that is the whole difficulty. A naive
/// delimiter count reads a media range inside a string as a terminator, and
/// would have called the second failure above correct.
fn scan_comments(source: &str) -> Result<(), String> {
    let bytes = source.as_bytes();
    let mut index = 0;
    let mut line = 1;
    // Line numbers of the comments currently open, innermost last.
    let mut open: Vec<usize> = Vec::new();

    let starts_with = |at: usize, pattern: &str| bytes[at..].starts_with(pattern.as_bytes());

    while index < bytes.len() {
        if bytes[index] == b'\n' {
            line += 1;
            index += 1;
            continue;
        }

        // Inside a comment only delimiters matter; quotes are ordinary text.
        if !open.is_empty() {
            if starts_with(index, "/*") {
                open.push(line);
                index += 2;
            } else if starts_with(index, "*/") {
                open.pop();
                index += 2;
            } else {
                index += 1;
            }
            continue;
        }

        if starts_with(index, "//") {
            while index < bytes.len() && bytes[index] != b'\n' {
                index += 1;
            }
        } else if starts_with(index, "/*") {
            open.push(line);
            index += 2;
        } else if starts_with(index, "*/") {
            return Err(format!("line {line}: terminator with no comment open"));
        } else if starts_with(index, "\"\"\"") {
            index += 3;
            while index < bytes.len() && !starts_with(index, "\"\"\"") {
                if bytes[index] == b'\n' {
                    line += 1;
                }
                index += 1;
            }
            index = bytes.len().min(index + 3);
        } else if bytes[index] == b'"' || bytes[index] == b'\'' {
            let quote = bytes[index];
            index += 1;
            while index < bytes.len() && bytes[index] != quote {
                // A backslash escapes the next byte, including a quote.
                if bytes[index] == b'\\' {
                    index += 1;
                }
                if index < bytes.len() && bytes[index] == b'\n' {
                    line += 1;
                }
                index += 1;
            }
            index += 1;
        } else {
            index += 1;
        }
    }

    match open.first() {
        Some(line) => Err(format!("comment opened at line {line} is never closed")),
        None => Ok(()),
    }
}

/// Every file under `root` with the given extension, skipping build output.
fn sources_with_extension(root: &Path, extension: &str) -> Vec<PathBuf> {
    let mut found = Vec::new();
    let mut pending = vec![root.to_path_buf()];

    while let Some(directory) = pending.pop() {
        let Ok(entries) = std::fs::read_dir(&directory) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                let name = entry.file_name().to_string_lossy().into_owned();
                if name != "target" && name != "build" && !name.starts_with('.') {
                    pending.push(path);
                }
            } else if path.extension().is_some_and(|e| e == extension) {
                found.push(path);
            }
        }
    }
    found
}

#[cfg(test)]
mod scanner {
    use super::scan_comments;

    #[test]
    fn plain_code_and_comments_balance() {
        assert!(scan_comments("val x = 1 // note\n/* block */\n/** doc */\n").is_ok());
    }

    /// The false positive a naive count produces, and why literals are skipped.
    #[test]
    fn a_delimiter_inside_a_string_is_not_a_delimiter() {
        assert!(scan_comments("val accept = \"text/css,*/*;q=0.1\"\n").is_ok());
        assert!(scan_comments("val raw = \"\"\"a */ b /* c\"\"\"\n").is_ok());
        assert!(scan_comments("val quote = \"he said \\\" */ \"\n").is_ok());
    }

    /// The failure that reached CI.
    #[test]
    fn a_media_range_in_a_doc_comment_is_caught() {
        let source =
            "/**\n * demanded an GLOB, so a message\n */\nclass A\n".replace("GLOB", "`image/*`");
        assert!(scan_comments(&source).is_err());
    }

    /// Kotlin genuinely nests, so deliberate nesting is legal.
    #[test]
    fn deliberate_nesting_is_allowed() {
        assert!(scan_comments("/* outer /* inner */ still outer */\n").is_ok());
    }

    #[test]
    fn a_stray_terminator_is_caught() {
        assert!(scan_comments("class A\n*/\n").is_err());
    }
}
