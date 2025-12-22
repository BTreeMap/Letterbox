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

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        // Copy test EML from assets to a location accessible via FileProvider
        val assetsManager = context.assets
        val emlContent = assetsManager.open("test_simple.eml").bufferedReader().use { it.readText() }
        
        // Create test file in internal storage
        testEmlFile = File(context.cacheDir, "test_simple.eml")
        testEmlFile.writeText(emlContent)
        
        // Create content URI for the test file
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
            composeTestRule.waitUntil(timeoutMillis = 10000) {
                try {
                    composeTestRule.onNodeWithText("Test Email - Simple", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
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
            composeTestRule.waitUntil(timeoutMillis = 10000) {
                try {
                    composeTestRule.onNodeWithText("Test Email - Simple", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
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
            composeTestRule.waitUntil(timeoutMillis = 10000) {
                try {
                    composeTestRule.onNodeWithText("Test Email - Simple", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Verify the activity is still active (not crashed)
            assert(scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            
            // Verify email content is displayed
            composeTestRule.onNodeWithText("From: sender@example.com", substring = true).assertIsDisplayed()
        }
    }
}
