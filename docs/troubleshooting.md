# Troubleshooting Guide

This document contains solutions to common issues encountered while developing or using the Letterbox app.

## App crashes when opening email files

### Symptoms
- App crashes immediately after user supplies an email file
- Crash occurs when opening from SAF framework or choosing the app as default opener
- WebView fails to render HTML content

### Root Cause
The `shouldInterceptRequest` method in `EmailWebView` was unconditionally returning a 403 Forbidden response for all non-cid: URLs. This blocked all HTTP/HTTPS requests even when `allowNetworkLoads` was true, and also blocked other necessary WebView requests that the WebView needs to function properly.

### Solution
The fix involved updating the `shouldInterceptRequest` logic in `EmailDetailScreen.kt`:

1. **Allow HTTP/HTTPS requests when remote images are enabled**: When `allowNetworkLoads` is true, the method now returns `null` for HTTP/HTTPS URLs, allowing WebView to handle them normally (with privacy proxy if configured).

2. **Block HTTP/HTTPS requests for security**: When `allowNetworkLoads` is false, the method returns a 403 Forbidden response for HTTP/HTTPS URLs to protect privacy.

3. **Let WebView handle other schemes**: For other URL schemes (like data:, javascript:, etc.), the method returns `null` to let WebView's default behavior take over.

### Code Changes
```kotlin
override fun shouldInterceptRequest(
    view: WebView?,
    request: WebResourceRequest?
): WebResourceResponse? {
    val url = request?.url?.toString() ?: return null

    // Intercept cid: URLs for inline images
    if (url.startsWith("cid:")) {
        // ... handle cid: URLs
    }

    // Allow network loads when explicitly enabled (for remote images)
    if (allowNetworkLoads && (url.startsWith("http://") || url.startsWith("https://"))) {
        return null // Let WebView handle it normally
    }

    // Block all other external requests with a clear error
    if (url.startsWith("http://") || url.startsWith("https://")) {
        return WebResourceResponse(/* 403 Forbidden */)
    }

    // For other schemes, return null to let WebView handle them
    return null
}
```

### Testing
To prevent this regression, end-to-end tests were added in `EmailOpeningE2ETest.kt`:

- `openEmlFile_viaActionView_displaysEmailContent` - Tests opening via ACTION_VIEW intent
- `openEmlFile_viaActionSend_displaysEmailContent` - Tests opening via ACTION_SEND intent
- `openEmlFile_displaysHtmlContentWithoutCrash` - Specifically verifies HTML rendering doesn't crash

Test EML files are located in `app/src/androidTest/assets/`:
- `test_simple.eml` - Basic HTML email
- `test_with_images.eml` - Email with remote images

### Verification
Run the instrumented tests to verify the fix:
```bash
./gradlew :app:connectedAndroidTest
```

Or run unit tests:
```bash
./gradlew :app:testProdDebugUnitTest
```

## WebView not displaying HTML content

### Symptoms
- Email content area appears blank
- HTML body doesn't render

### Possible Causes
1. **Incorrect Content-Type**: Ensure the HTML is loaded with proper MIME type (`text/html`)
2. **Base URL issues**: WebView may need a base URL for relative resources
3. **JavaScript disabled**: Some email HTML may require JavaScript (though it's disabled for security)

### Solution
The app uses `loadDataWithBaseURL(null, html, "text/html", "utf-8", null)` which should handle most cases. If issues persist:

1. Check the HTML content is valid
2. Verify inline resources use cid: URLs and are handled by `shouldInterceptRequest`
3. Review WebView settings in `EmailWebView` composable

## Remote images not loading

### Symptoms
- Images in emails don't appear even after clicking "Show Images"
- Image placeholder icons visible but images don't load

### Root Cause
The `shouldInterceptRequest` method blocks HTTP/HTTPS requests by default for privacy.

### Solution
This is expected behavior. Users must:
1. Click the "Show Images" button in the remote images banner
2. Images will then load through DuckDuckGo privacy proxy (if enabled)

The setting can be changed in Settings:
- "Always load remote images" - Loads images automatically
- "Use privacy proxy" - Routes images through DuckDuckGo

## Test failures

### Running instrumented tests
Instrumented tests require an Android device or emulator:

```bash
# List available devices
adb devices

# Run tests on connected device
./gradlew :app:connectedAndroidTest

# Or use managed device
./gradlew :app:pixel7Api34ProdDebugAndroidTest
```

### Running unit tests
Unit tests can run on the host machine:

```bash
./gradlew :app:testProdDebugUnitTest
```

These require the Rust library to be built:
```bash
cd rust/letterbox-core
cargo build --release --lib
```

## Build issues

### Native library not found
If you see errors about missing native library:

1. Build the Rust library:
   ```bash
   ./gradlew :app:cargoHostBuild  # For unit tests
   ./gradlew :app:cargoNdkBuild -PrustBuild=true  # For Android
   ```

2. Ensure cargo-ndk is installed:
   ```bash
   cargo install cargo-ndk
   ```

### Gradle sync failures
If Gradle sync fails:

1. Clean the project:
   ```bash
   ./gradlew clean
   ```

2. Invalidate caches in Android Studio: File > Invalidate Caches / Restart

3. Check Gradle version compatibility in `gradle/wrapper/gradle-wrapper.properties`

## For more help

- Check existing issues: https://github.com/BTreeMap/Letterbox/issues
- Read the main README: [README.md](../README.md)
- Review architecture: [architecture.md](architecture.md)
