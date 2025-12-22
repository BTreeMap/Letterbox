# Remote Image Loading and Privacy Proxy

## Overview

Letterbox now includes support for loading remote images in emails with privacy protection through DuckDuckGo's image proxy service.

## Features

### Default Behavior
- Remote images (http:// and https://) are **blocked by default** to protect your privacy
- When an email contains remote images, a banner appears: "Remote images are hidden"
- Click "Show" to load images for the current email only

### Privacy Protection
- When images are loaded, they're routed through DuckDuckGo's privacy proxy by default
- This prevents the email sender from seeing your IP address
- HTTP images are automatically upgraded to HTTPS
- Tracking headers (Referer, User-Agent) are stripped

### Settings

Access Settings from the main screen menu:

#### Always load remote images
- **Default: OFF**
- When enabled, remote images will load automatically in all emails
- Images are still proxied through DuckDuckGo for privacy

#### Use privacy proxy
- **Default: ON**
- When enabled, all remote images are loaded through DuckDuckGo's proxy
- When disabled, images load directly from their source (not recommended - exposes your IP)

## Technical Details

### DuckDuckGo Image Proxy

The proxy uses DuckDuckGo's `external-content.duckduckgo.com/iu/` endpoint:

```
Original URL: https://example.com/image.jpg
Proxied URL:  https://external-content.duckduckgo.com/iu/?u=https%3A%2F%2Fexample.com%2Fimage.jpg
```

### Privacy Benefits
1. **IP Masking**: Your IP address is not visible to the email sender
2. **Header Stripping**: Tracking headers are removed
3. **Protocol Upgrade**: HTTP images are served over HTTPS
4. **No JavaScript**: The WebView keeps JavaScript disabled for security

### Inline Images (cid: URLs)
- Inline images embedded in the email are always displayed
- These are not affected by the remote image settings
- No network access is required for inline images

## Security Considerations

- The WebView remains sandboxed with:
  - JavaScript disabled
  - File access disabled
  - Network loads only enabled when explicitly requested
- SAF (Storage Access Framework) permissions are persisted when opening files
- Only specific MIME types are accepted by the file picker

## Implementation

The feature is implemented in several layers:

1. **UserPreferencesRepository**: Persists settings using Jetpack DataStore
2. **HtmlImageRewriter**: Rewrites HTML to proxy images through DuckDuckGo
3. **EmailViewModel**: Tracks session image loading state and detects remote images
4. **EmailDetailScreen**: Displays privacy banner and controls image loading
5. **WebView**: Configured to allow network loads only when appropriate

## Testing

Run the unit tests:
```bash
./gradlew :app:testProdDebugUnitTest --tests "org.joefang.letterbox.ui.HtmlImageRewriterTest"
```

All tests cover:
- URL proxying for http/https images
- Preservation of cid: inline images
- URL encoding with special characters
- Detection of remote images
