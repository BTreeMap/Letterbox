package org.joefang.letterbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end test for opening EML files.
 * 
 * This test verifies that:
 * - The app can open EML files from content:// URIs (SAF)
 * - The app can open EML files via ACTION_VIEW intents (default file opener)
 * - The app correctly parses and displays email content
 * - The WebView renders HTML content without crashing
 * 
 * This test prevents regressions of the crash that occurred when the
 * WebView's shouldInterceptRequest was blocking all requests.
 */
@RunWith(AndroidJUnit4::class)
class EmailOpeningE2ETest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var testEmlFile: File
    private lateinit var testEmlUri: Uri

    companion object {
        private const val EMAIL_LOAD_TIMEOUT_MS = 10000L
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        // Copy test EML from test APK assets to a location accessible via FileProvider
        // Note: Test assets are in the test APK, not the application APK, so we need
        // to use InstrumentationRegistry.getInstrumentation().context to access them
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val emlContent = testContext.assets.open("test_simple.eml").bufferedReader().use { it.readText() }
        
        // Create test file in the "shared" subdirectory of cache, which is configured 
        // in file_paths.xml for FileProvider access
        val sharedDir = File(context.cacheDir, "shared")
        sharedDir.mkdirs()
        testEmlFile = File(sharedDir, "test_simple.eml")
        testEmlFile.writeText(emlContent)
        
        // Create content URI for the test file using the app's FileProvider
        testEmlUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            testEmlFile
        )
    }

    @Test
    fun openEmlFile_viaActionView_displaysEmailContent() {
        // Create ACTION_VIEW intent with the test EML file
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(testEmlUri, "message/rfc822")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        // Launch the activity with the intent
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // Wait for the email to load and verify content is displayed
            composeTestRule.waitUntil(timeoutMillis = EMAIL_LOAD_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithText("Test Email - Simple", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Wait for Compose to settle before asserting visibility
            composeTestRule.waitForIdle()
            
            // Verify email details are displayed
            composeTestRule.onNodeWithText("Test Email - Simple", substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithText("From: sender@example.com", substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithText("To: recipient@example.com", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun openEmlFile_viaActionSend_displaysEmailContent() {
        // Create ACTION_SEND intent with the test EML file
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "message/rfc822"
            putExtra(Intent.EXTRA_STREAM, testEmlUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        // Launch the activity with the intent
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // Wait for the email to load and verify content is displayed
            composeTestRule.waitUntil(timeoutMillis = EMAIL_LOAD_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithText("Test Email - Simple", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Wait for Compose to settle before asserting visibility
            composeTestRule.waitForIdle()
            
            // Verify email details are displayed
            composeTestRule.onNodeWithText("Test Email - Simple", substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithText("From: sender@example.com", substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithText("To: recipient@example.com", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun openEmlFile_displaysHtmlContentWithoutCrash() {
        // This test specifically verifies that HTML content is rendered without crash
        // Previously, the shouldInterceptRequest was blocking all requests causing crashes
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
                    composeTestRule.onNodeWithText("Test Email - Simple", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Wait for Compose to settle before asserting visibility
            composeTestRule.waitForIdle()
            
            // Verify the activity is still active (not crashed)
            val isResumed = scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            assertTrue("Activity should be in RESUMED state but is ${scenario.state}", isResumed)
            
            // Wait for and verify email header is displayed
            composeTestRule.waitUntil(timeoutMillis = EMAIL_LOAD_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithText("From: sender@example.com", substring = true).assertIsDisplayed()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
        }
    }
}
