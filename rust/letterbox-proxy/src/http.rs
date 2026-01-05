//! HTTP client for image fetching.
//!
//! This module provides HTTP/HTTPS image fetching functionality.
//! It supports two modes:
//!
//! 1. **Simple mode**: Uses reqwest for direct HTTP requests (fallback)
//! 2. **Tunnel mode**: Uses the WARP tunnel with smoltcp and rustls
//!
//! ## Security Features
//!
//! - Strips all cookies and tracking headers
//! - Enforces content-type allowlist
//! - Limits response size to prevent DoS
//! - Limits redirects to prevent loops
//! - No referrer forwarding

use crate::config::FetchLimits;
use crate::error::ProxyError;
use crate::ImageResponse;
use std::time::Duration;

/// Fetch an image using a simple HTTP client (fallback mode).
///
/// This function uses reqwest directly without the WireGuard tunnel.
/// It's used as a fallback when the tunnel is not available or for
/// initial testing.
///
/// ## Privacy
///
/// Even in simple mode, this function:
/// - Strips cookies
/// - Removes referrer headers
/// - Uses a generic user agent
pub async fn fetch_image_simple(url: &str) -> Result<ImageResponse, ProxyError> {
    fetch_image_with_limits(url, &FetchLimits::default()).await
}

/// Fetch an image with custom limits.
pub async fn fetch_image_with_limits(
    url: &str,
    limits: &FetchLimits,
) -> Result<ImageResponse, ProxyError> {
    // Build client with privacy-preserving settings
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(limits.timeout_seconds as u64))
        .redirect(reqwest::redirect::Policy::limited(
            limits.max_redirects as usize,
        ))
        .user_agent("Mozilla/5.0 (compatible; ImageProxy/1.0)")
        .referer(false) // Don't send referer
        .build()
        .map_err(|e| ProxyError::HttpError {
            status_code: 0,
            details: format!("Failed to create HTTP client: {}", e),
        })?;

    // Make the request
    let response = client
        .get(url)
        .header("Accept", "image/*")
        .send()
        .await
        .map_err(|e: reqwest::Error| {
            if e.is_timeout() {
                ProxyError::Timeout {
                    seconds: limits.timeout_seconds,
                }
            } else if e.is_redirect() {
                ProxyError::TooManyRedirects {
                    count: limits.max_redirects,
                    max_count: limits.max_redirects,
                }
            } else {
                ProxyError::HttpError {
                    status_code: e
                        .status()
                        .map(|s: reqwest::StatusCode| s.as_u16())
                        .unwrap_or(0),
                    details: e.to_string(),
                }
            }
        })?;

    // Check status
    let status = response.status();
    if !status.is_success() {
        return Err(ProxyError::HttpError {
            status_code: status.as_u16(),
            details: format!(
                "HTTP {} {}",
                status.as_u16(),
                status.canonical_reason().unwrap_or("Unknown")
            ),
        });
    }

    // Get final URL (after redirects)
    let final_url = response.url().to_string();

    // Check content type
    let content_type = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v: &reqwest::header::HeaderValue| v.to_str().ok())
        .unwrap_or("application/octet-stream")
        .to_string();

    // Normalize content type (remove parameters)
    let mime_type = content_type
        .split(';')
        .next()
        .unwrap_or(&content_type)
        .trim()
        .to_string();

    if !limits.is_content_type_allowed(&mime_type) {
        return Err(ProxyError::InvalidContentType {
            content_type: mime_type,
        });
    }

    // Check content length if available
    if let Some(len) = response.content_length() {
        if len > limits.max_size {
            return Err(ProxyError::ResponseTooLarge {
                size: len,
                max_size: limits.max_size,
            });
        }
    }

    // Read the full response body
    let data = response
        .bytes()
        .await
        .map_err(|e: reqwest::Error| ProxyError::HttpError {
            status_code: 0,
            details: format!("Failed to read response: {}", e),
        })?;

    if data.len() as u64 > limits.max_size {
        return Err(ProxyError::ResponseTooLarge {
            size: data.len() as u64,
            max_size: limits.max_size,
        });
    }

    Ok(ImageResponse {
        mime_type,
        data: data.to_vec(),
        from_cache: false,
        final_url,
    })
}

/// Guess the MIME type from file magic bytes.
pub fn guess_mime_type(data: &[u8]) -> Option<&'static str> {
    if data.len() < 4 {
        return None;
    }

    // Check magic bytes
    match &data[..4] {
        // PNG
        [0x89, 0x50, 0x4E, 0x47] => Some("image/png"),
        // JPEG
        [0xFF, 0xD8, 0xFF, _] => Some("image/jpeg"),
        // GIF
        [0x47, 0x49, 0x46, 0x38] => Some("image/gif"),
        // WebP
        [0x52, 0x49, 0x46, 0x46] if data.len() >= 12 && &data[8..12] == b"WEBP" => {
            Some("image/webp")
        }
        // BMP
        [0x42, 0x4D, _, _] => Some("image/bmp"),
        // ICO
        [0x00, 0x00, 0x01, 0x00] => Some("image/x-icon"),
        _ => {
            // Check for SVG (text-based)
            if data.len() >= 5 {
                let start = String::from_utf8_lossy(&data[..std::cmp::min(100, data.len())]);
                if start.contains("<svg") || start.contains("<?xml") {
                    return Some("image/svg+xml");
                }
            }
            None
        }
    }
}

/// Validate that response data matches the expected MIME type.
pub fn validate_image_data(data: &[u8], claimed_mime: &str) -> bool {
    if data.is_empty() {
        return false;
    }

    // For SVG, we just check it looks like XML/SVG
    if claimed_mime == "image/svg+xml" {
        let start = String::from_utf8_lossy(&data[..std::cmp::min(100, data.len())]);
        return start.contains("<svg")
            || start.contains("<?xml")
            || start.contains("<!DOCTYPE svg");
    }

    // For binary formats, check magic bytes
    if let Some(detected) = guess_mime_type(data) {
        // Allow slight mismatches (e.g., x-icon vs vnd.microsoft.icon)
        let claimed_base = claimed_mime.split('/').nth(1).unwrap_or("");
        let detected_base = detected.split('/').nth(1).unwrap_or("");

        // Exact match or both are icon types
        detected == claimed_mime
            || (claimed_base.contains("icon") && detected_base.contains("icon"))
    } else {
        // Can't detect, trust the server
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_guess_mime_type_png() {
        let png_header = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
        assert_eq!(guess_mime_type(&png_header), Some("image/png"));
    }

    #[test]
    fn test_guess_mime_type_jpeg() {
        let jpeg_header = [0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10];
        assert_eq!(guess_mime_type(&jpeg_header), Some("image/jpeg"));
    }

    #[test]
    fn test_guess_mime_type_gif() {
        let gif_header = [0x47, 0x49, 0x46, 0x38, 0x39, 0x61];
        assert_eq!(guess_mime_type(&gif_header), Some("image/gif"));
    }

    #[test]
    fn test_guess_mime_type_bmp() {
        let bmp_header = [0x42, 0x4D, 0x00, 0x00];
        assert_eq!(guess_mime_type(&bmp_header), Some("image/bmp"));
    }

    #[test]
    fn test_guess_mime_type_ico() {
        let ico_header = [0x00, 0x00, 0x01, 0x00];
        assert_eq!(guess_mime_type(&ico_header), Some("image/x-icon"));
    }

    #[test]
    fn test_guess_mime_type_svg() {
        let svg_data = b"<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\">";
        assert_eq!(guess_mime_type(svg_data), Some("image/svg+xml"));
    }

    #[test]
    fn test_guess_mime_type_svg_direct() {
        let svg_data = b"<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\">";
        assert_eq!(guess_mime_type(svg_data), Some("image/svg+xml"));
    }

    #[test]
    fn test_guess_mime_type_unknown() {
        let unknown_data = [0x00, 0x01, 0x02, 0x03];
        assert_eq!(guess_mime_type(&unknown_data), None);
    }

    #[test]
    fn test_guess_mime_type_too_short() {
        let short_data = [0x89, 0x50];
        assert_eq!(guess_mime_type(&short_data), None);
    }

    #[test]
    fn test_validate_image_data_png() {
        let png_data = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
        assert!(validate_image_data(&png_data, "image/png"));
        assert!(!validate_image_data(&png_data, "image/jpeg"));
    }

    #[test]
    fn test_validate_image_data_svg() {
        let svg_data = b"<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\">";
        assert!(validate_image_data(svg_data, "image/svg+xml"));
    }

    #[test]
    fn test_validate_image_data_empty() {
        assert!(!validate_image_data(&[], "image/png"));
    }

    #[test]
    fn test_validate_icon_types() {
        let ico_data = [0x00, 0x00, 0x01, 0x00, 0x01, 0x00];
        assert!(validate_image_data(&ico_data, "image/x-icon"));
        assert!(validate_image_data(&ico_data, "image/vnd.microsoft.icon"));
    }

    #[tokio::test]
    async fn test_fetch_limits() {
        let limits = FetchLimits {
            max_size: 1024,
            max_redirects: 3,
            timeout_seconds: 10,
            allowed_content_types: vec!["image/png".to_string()],
        };

        assert!(limits.is_content_type_allowed("image/png"));
        assert!(!limits.is_content_type_allowed("image/jpeg"));
    }
}
