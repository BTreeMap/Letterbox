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

### Instrumented tests crash with "Process crashed"

#### Symptoms
- Instrumented tests start but crash during execution
- Error message: "Instrumentation run failed due to Process crashed"
- Some tests pass before the crash occurs
- The failing test may not be the one that actually caused the crash

#### Root Cause
FFI (Foreign Function Interface) calls to the Rust native library may fail if the native library is not available or fails to load. In Kotlin/Java, when a native library fails to load, it throws `UnsatisfiedLinkError` or `ExceptionInInitializerError`, which are `Error` types, NOT `Exception` types.

If FFI calls are wrapped in `catch (e: Exception)` blocks, these errors are **NOT caught**, causing the process to crash.

#### Solution
All FFI calls must catch both `Exception` AND `Error` types:

```kotlin
val result = try {
    someFfiFunction()
} catch (e: Exception) {
    fallbackValue
} catch (e: UnsatisfiedLinkError) {
    // Native library not available
    fallbackValue
} catch (e: ExceptionInInitializerError) {
    // Library initialization failed
    fallbackValue
}
```

The following FFI functions in this codebase require this pattern:
- `parseEml()` / `parseEmlFromPath()` - Already properly handled
- `extractRemoteImages()` - Fixed in EmailViewModel.kt
- `rewriteImageUrls()` - Fixed in EmailDetailScreen.kt

#### Prevention
When adding new FFI calls, always:
1. Wrap in try-catch
2. Catch both `Exception` and `Error` types (`UnsatisfiedLinkError`, `ExceptionInInitializerError`)
3. Provide a sensible fallback (e.g., empty list, original content, false)

### Test assets not found (FileNotFoundException)

#### Symptoms
- Instrumented tests fail with `java.io.FileNotFoundException: test_simple.eml`
- Tests that access test assets crash with asset not found errors

#### Root Cause
Test assets (files in `app/src/androidTest/assets/`) are packaged into the **test APK**, not the application APK. Using `ApplicationProvider.getApplicationContext().assets` accesses the **application APK's assets**, which doesn't contain test assets.

#### Solution
Use `InstrumentationRegistry.getInstrumentation().context` to access test APK assets:

```kotlin
// Wrong: Uses application context (app APK assets)
val context = ApplicationProvider.getApplicationContext()
val content = context.assets.open("test_file.eml")

// Correct: Uses instrumentation context (test APK assets)
val testContext = InstrumentationRegistry.getInstrumentation().context
val content = testContext.assets.open("test_file.eml")
```

Note: For file operations (cache, shared preferences), continue using `ApplicationProvider.getApplicationContext()` as those need access to the app's storage.

### FileProvider path not configured (IllegalArgumentException)

#### Symptoms
- Tests fail with `java.lang.IllegalArgumentException: Failed to find configured root that contains /data/data/.../cache/file.eml`
- FileProvider.getUriForFile() throws exception

#### Root Cause
The FileProvider is configured with specific paths in `res/xml/file_paths.xml`. If you write a file to a directory that isn't configured, FileProvider cannot create a URI for it.

#### Solution
Write test files to a directory that's already configured in `file_paths.xml`:

```kotlin
// Wrong: Writing to cache root (not configured)
val file = File(context.cacheDir, "test.eml")

// Correct: Writing to "shared/" subdirectory (configured in file_paths.xml)
val sharedDir = File(context.cacheDir, "shared")
sharedDir.mkdirs()
val file = File(sharedDir, "test.eml")
```

Check `app/src/main/res/xml/file_paths.xml` to see which paths are configured:
```xml
<paths>
    <cache-path name="attachments" path="attachments/" />
    <cache-path name="shared" path="shared/" />
</paths>
```

### Test isolation issues (database state not reset)

#### Symptoms
- Tests that expect "empty state" fail inconsistently
- Tests pass individually but fail when run as a suite
- Error like "The component is not displayed!" for empty state message

#### Root Cause
Instrumented tests share the same application database. Tests that add data (like `EmailOpeningE2ETest` which opens email files) can leave data in the database that affects subsequent tests expecting an empty state.

#### Solution
Clear the database in `@Before` methods for tests that require a clean state:

```kotlin
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.joefang.letterbox.data.LetterboxDatabase
import org.junit.Before

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setup() {
        // Clear history database to ensure tests start with empty state
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            LetterboxDatabase.getInstance(context).historyItemDao().deleteAll()
        }
    }

    @Test
    fun homeScreen_displaysEmptyStateMessage() {
        // This test now reliably sees empty state
        composeTestRule.onNodeWithText("Open an .eml or .msg file").assertIsDisplayed()
    }
}
```

Note: Use `targetContext` (application context) for database operations, not `context` (test APK context).

#### Prevention
When writing new tests that depend on application state:
1. Always clear relevant data in `@Before` method
2. Consider using Room's in-memory database for unit tests
3. Use `@After` to clean up if necessary

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
