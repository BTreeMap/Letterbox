package org.joefang.letterbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.joefang.letterbox.data.UserPreferencesRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * End-to-end tests for link handling in the email WebView.
 * 
 * These tests verify that:
 * - The email loads correctly with links
 * - The email view doesn't crash when processing link elements
 * - The WebView handles various link types safely
 * 
 * Note: We cannot fully test external link opening in instrumented tests
 * since that would open external apps. These tests verify the email loads
 * correctly and handles link elements without errors.
 */
@RunWith(AndroidJUnit4::class)
class LinkHandlingE2ETest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var testEmlFile: File
    private lateinit var testEmlUri: Uri
    private lateinit var preferencesRepository: UserPreferencesRepository

    companion object {
        private const val EMAIL_LOAD_TIMEOUT_MS = 10000L
        
        // Test email with various link types
        private const val EMAIL_WITH_LINKS = """From: sender@example.com
To: recipient@example.com
Subject: Test Email - With Links
Date: Mon, 22 Dec 2025 10:00:00 +0000
Message-ID: <links123@example.com>
Content-Type: text/html; charset=UTF-8

<html>
<body>
<h1>Email with Links</h1>
<p>This email contains various types of links for testing:</p>

<h2>Regular HTTP/HTTPS Links</h2>
<p>Visit our <a href="https://example.com">website</a> for more information.</p>
<p>Check out this <a href="http://legacy.example.com/page">legacy link</a>.</p>

<h2>Email Links</h2>
<p>Contact us at <a href="mailto:support@example.com">support@example.com</a></p>
<p>Or email <a href="mailto:sales@example.com?subject=Inquiry">sales with subject</a></p>

<h2>Links with Special Characters</h2>
<p><a href="https://example.com/search?q=test&amp;lang=en">Search link</a></p>
<p><a href="https://example.com/path/to/page#section">Link with anchor</a></p>

<h2>Text without Links</h2>
<p>This paragraph has no links, just plain text.</p>
</body>
</html>
"""
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferencesRepository = UserPreferencesRepository(context)
        
        // Reset preferences
        runBlocking {
            preferencesRepository.setAlwaysLoadRemoteImages(false)
            preferencesRepository.setEnablePrivacyProxy(true)
            preferencesRepository.setCloudflareTermsAccepted(true)
        }
        
        // Create test file in the "shared" subdirectory of cache
        val sharedDir = File(context.cacheDir, "shared")
        sharedDir.mkdirs()
        testEmlFile = File(sharedDir, "test_with_links.eml")
        testEmlFile.writeText(EMAIL_WITH_LINKS)
        
        // Create content URI for the test file
        testEmlUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            testEmlFile
        )
    }

    @After
    fun tearDown() {
        // Clean up test file
        testEmlFile.delete()
    }

    @Test
    fun emailWithLinks_loadsSuccessfully() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(testEmlUri, "message/rfc822")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // Wait for email to load
            composeTestRule.waitUntil(timeoutMillis = EMAIL_LOAD_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithText("Test Email - With Links", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Verify email headers are displayed
            composeTestRule.onNodeWithText("From: sender@example.com", substring = true).assertExists()
            composeTestRule.onNodeWithText("To: recipient@example.com", substring = true).assertExists()
        }
    }

    @Test
    fun emailWithLinks_doesNotCrash() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(testEmlUri, "message/rfc822")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // Wait for email to load
            composeTestRule.waitUntil(timeoutMillis = EMAIL_LOAD_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithText("Test Email - With Links", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Wait for Compose to settle (allow WebView to process link elements)
            composeTestRule.waitForIdle()
            
            // Verify the activity is still active (not crashed)
            val isResumed = scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            org.junit.Assert.assertTrue("Activity should be in RESUMED state", isResumed)
        }
    }

    @Test
    fun emailWithComplexImages_loadsWithoutCrash() {
        // Use the complex images test email which has many links
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val emlContent = testContext.assets.open("test_complex_images.eml").bufferedReader().use { it.readText() }
        
        val complexEmlFile = File(context.cacheDir, "shared/test_complex_with_links.eml")
        complexEmlFile.writeText(emlContent)
        
        val complexEmlUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            complexEmlFile
        )
        
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(complexEmlUri, "message/rfc822")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                // Wait for email to load
                composeTestRule.waitUntil(timeoutMillis = EMAIL_LOAD_TIMEOUT_MS) {
                    try {
                        composeTestRule.onNodeWithText("Complex Email with Multiple Image Types", substring = true).assertExists()
                        true
                    } catch (e: AssertionError) {
                        false
                    }
                }
                
                // Wait for Compose to settle (allow WebView to process all elements)
                composeTestRule.waitForIdle()
                
                // Verify the activity is still active (not crashed)
                val isResumed = scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                org.junit.Assert.assertTrue("Activity should be in RESUMED state", isResumed)
            }
        } finally {
            complexEmlFile.delete()
        }
    }
}
