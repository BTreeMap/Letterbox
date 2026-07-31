//! Every accessor must survive any byte sequence, because the input is a file
//! someone was sent.
//!
//! This exists so the Kotlin fallback parser could be deleted. That fallback ran
//! whenever this parser returned an error and rebuilt the message from a naive
//! header split, silently indexing it with no date and no attachments. Removing
//! it makes this crate the only parser, so "liberal on input" has to be a
//! property that is tested rather than assumed.

use letterbox_core::{extract_remote_images, parse_eml, ParseError};

/// Drive the whole read surface, so a panic anywhere fails the test.
fn exercise_every_accessor(data: Vec<u8>) {
    let Ok(handle) = parse_eml(data) else {
        // Rejection is a fine outcome; crashing is not.
        return;
    };

    let _ = handle.subject();
    let _ = handle.from();
    let _ = handle.to();
    let _ = handle.cc();
    let _ = handle.reply_to();
    let _ = handle.message_id();
    let _ = handle.date();
    let _ = handle.date_timestamp();
    let _ = handle.sender_info();
    let _ = handle.recipient_info();
    let _ = handle.body_preview();
    let _ = handle.body_text();
    let _ = handle.attachment_count();

    if let Some(html) = handle.body_html() {
        // The renderer feeds every body straight into this.
        let _ = extract_remote_images(html);
    }

    for info in handle.get_attachments() {
        let _ = info.size;
    }
    // One past the end, and a value that would wrap a signed cast.
    for index in [0, handle.attachment_count(), u32::MAX] {
        let _ = handle.get_attachment_content(index);
    }
    for meta in handle.get_resource_metadata() {
        let _ = handle.get_resource(meta.cid.clone());
        let _ = handle.get_resource_content_type(meta.cid);
    }
    for cid in handle.get_resource_ids() {
        let _ = handle.get_resource(cid);
    }
    let _ = handle.get_resource(String::new());
}

#[test]
fn malformed_and_hostile_messages_never_panic() {
    let cases: Vec<Vec<u8>> = vec![
        b"not a valid email".to_vec(),
        b"\0\0\0\0".to_vec(),
        b"Subject:".to_vec(),
        b"Subject: \r\n\r\n".to_vec(),
        b"\r\n\r\n".to_vec(),
        // Header with no body, body with no header.
        b"From: a@b.c".to_vec(),
        b"\r\n\r\njust a body".to_vec(),
        // Invalid UTF-8 throughout.
        vec![0xFF, 0xFE, 0xFD, 0xFC, 0x0D, 0x0A, 0x0D, 0x0A, 0x80, 0x81],
        // Truncated multipart: boundary opened, never closed.
        b"Content-Type: multipart/mixed; boundary=\"x\"\r\n\r\n--x\r\nContent-Type: text/plain\r\n\r\nhi".to_vec(),
        // Boundary that never appears.
        b"Content-Type: multipart/mixed; boundary=\"nope\"\r\n\r\nbody".to_vec(),
        // Attachment declaring a name but carrying nothing.
        b"Content-Type: multipart/mixed; boundary=\"x\"\r\n\r\n--x\r\nContent-Disposition: attachment; filename=\"a.bin\"\r\n\r\n\r\n--x--\r\n".to_vec(),
        // Base64 that is not base64.
        b"Content-Transfer-Encoding: base64\r\n\r\n!!!!not base64!!!!".to_vec(),
        // Charset nobody has.
        b"Content-Type: text/plain; charset=\"nonexistent-charset\"\r\n\r\nhello".to_vec(),
        // Unterminated and empty address forms.
        b"From: \"unclosed\r\nTo: undisclosed-recipients:;\r\n\r\nbody".to_vec(),
        b"From: <>\r\nTo: ,,,\r\n\r\nbody".to_vec(),
        // A Content-ID that is only delimiters.
        b"Content-Type: multipart/related; boundary=\"x\"\r\n\r\n--x\r\nContent-ID: <>\r\nContent-Type: image/png\r\n\r\nPNG\r\n--x--\r\n".to_vec(),
    ];

    for case in cases {
        exercise_every_accessor(case);
    }
}

/// A date far outside any calendar must not overflow the millisecond conversion.
#[test]
fn an_absurd_date_saturates_rather_than_overflowing() {
    for date in [
        "Date: Mon, 1 Jan 999999999 00:00:00 +0000",
        "Date: Mon, 1 Jan -999999999 00:00:00 +0000",
        "Date: 99999999999999999999",
    ] {
        let message = format!("{date}\r\nSubject: t\r\n\r\nbody");
        exercise_every_accessor(message.into_bytes());
    }
}

/// Deep nesting must not recurse without bound.
#[test]
fn deeply_nested_multiparts_never_panic() {
    let depth = 200;
    let mut message = String::new();
    for level in 0..depth {
        message.push_str(&format!(
            "Content-Type: multipart/mixed; boundary=\"b{level}\"\r\n\r\n--b{level}\r\n"
        ));
    }
    message.push_str("Content-Type: text/plain\r\n\r\nbottom\r\n");
    for level in (0..depth).rev() {
        message.push_str(&format!("--b{level}--\r\n"));
    }
    exercise_every_accessor(message.into_bytes());
}

/// The HTML the renderer receives is attacker-controlled too.
#[test]
fn hostile_html_never_panics_the_image_extractor() {
    for html in [
        "",
        "<img",
        "<img src=",
        "<img src=\"\">",
        "<img src=\"javascript:alert(1)\" width=\"x\" height=\"-1\">",
        "<img src=\"http://e.com/a.png\" width=\"99999999999999999999\" height=\"1\">",
        &"<div>".repeat(5_000),
        "<img src=\"http://e.com/\u{0}\u{FFFD}.png\">",
    ] {
        let _ = extract_remote_images(html.to_string());
    }
}

/// Empty input is the one rejection the app relies on being an error.
#[test]
fn empty_input_is_rejected_not_invented() {
    assert!(matches!(parse_eml(Vec::new()), Err(ParseError::Empty)));
}

/// Liberality has a floor: anything non-empty must yield a handle, because the
/// app no longer has a second parser to fall back to.
#[test]
fn any_non_empty_input_yields_a_handle() {
    for case in [
        b"x".to_vec(),
        b"\0".to_vec(),
        vec![0xFF],
        b"not a valid email".to_vec(),
    ] {
        let refused = parse_eml(case.clone()).is_err();
        assert!(
            !refused,
            "refused to parse {case:?}, which would now show as an error to the user"
        );
    }
}
