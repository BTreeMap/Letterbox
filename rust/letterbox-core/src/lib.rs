use std::collections::HashMap;
use std::sync::{Arc, Mutex};

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum ParseError {
    #[error("invalid message")]
    Invalid,
}

#[derive(Clone, Debug)]
pub struct EmailHandle {
    inner: Arc<Mutex<ParsedMessage>>,
}

#[derive(Debug)]
struct ParsedMessage {
    subject: String,
    body_html: Option<String>,
    inline_assets: HashMap<String, Vec<u8>>,
}

pub fn parse_eml(data: &[u8]) -> Result<EmailHandle, ParseError> {
    if data.is_empty() {
        return Err(ParseError::Invalid);
    }

    let text = String::from_utf8_lossy(data);
    let subject = extract_subject(&text).unwrap_or_else(|| "Untitled".to_string());
    let body_html = extract_body(&text);

    let parsed = ParsedMessage {
        subject,
        body_html,
        inline_assets: HashMap::new(),
    };

    Ok(EmailHandle {
        inner: Arc::new(Mutex::new(parsed)),
    })
}

impl EmailHandle {
    pub fn subject(&self) -> String {
        self.inner
            .lock()
            .map(|msg| msg.subject.clone())
            .unwrap_or_default()
    }

    pub fn body_html(&self) -> Option<String> {
        self.inner.lock().ok().and_then(|msg| msg.body_html.clone())
    }

    pub fn get_resource(&self, cid: &str) -> Option<Vec<u8>> {
        self.inner
            .lock()
            .ok()
            .and_then(|msg| msg.inline_assets.get(cid).cloned())
    }
}

fn extract_subject(text: &str) -> Option<String> {
    text.lines()
        .find(|line| line.to_ascii_lowercase().starts_with("subject:"))
        .map(|line| line.splitn(2, ':').nth(1).unwrap_or("").trim().to_string())
        .filter(|s| !s.is_empty())
}

fn extract_body(text: &str) -> Option<String> {
    text.split("\n\n")
        .nth(1)
        .map(|body| body.trim().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use once_cell::sync::Lazy;

    static SAMPLE: Lazy<&'static str> = Lazy::new(|| "Subject: Hello\n\n<p>Body</p>");

    #[test]
    fn parses_subject_and_body() {
        let handle = parse_eml(SAMPLE.as_bytes()).expect("should parse");
        assert_eq!(handle.subject(), "Hello");
        assert_eq!(handle.body_html(), Some("<p>Body</p>".to_string()));
    }

    #[test]
    fn rejects_empty_payload() {
        let err = parse_eml(&[]).unwrap_err();
        assert_eq!(err, ParseError::Invalid);
    }

    #[test]
    fn returns_none_for_missing_cid() {
        let handle = parse_eml(SAMPLE.as_bytes()).expect("should parse");
        assert_eq!(handle.get_resource("cid:image"), None);
    }
}
