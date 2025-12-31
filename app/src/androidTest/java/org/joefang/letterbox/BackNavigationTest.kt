package org.joefang.letterbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.joefang.letterbox.data.LetterboxDatabase
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end tests for back navigation behavior.
 * 
 * This test verifies that:
 * - When opening an email from the history list, pressing back returns to the list
 * - When opening an email from an external intent (ACTION_VIEW/ACTION_SEND), 
 *   pressing the back arrow button handles navigation correctly
 * - The navigation is consistent and predictable for users
 */
@RunWith(AndroidJUnit4::class)
class BackNavigationTest {

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
        
        // Clear history database to ensure tests start with clean state
        runBlocking {
            LetterboxDatabase.getInstance(context).historyItemDao().deleteAll()
        }
        
        // Copy test EML from test APK assets to a location accessible via FileProvider
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val emlContent = testContext.assets.open("test_simple.eml").bufferedReader().use { it.readText() }
        
        // Create test file in the "shared" subdirectory of cache
        val sharedDir = File(context.cacheDir, "shared")
        sharedDir.mkdirs()
        testEmlFile = File(sharedDir, "test_simple.eml")
        testEmlFile.writeText(emlContent)
        
        // Create content URI for the test file
        testEmlUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            testEmlFile
        )
    }

    @After
    fun teardown() {
        // Clean up test files and database
        testEmlFile.delete()
        runBlocking {
            LetterboxDatabase.getInstance(context).historyItemDao().deleteAll()
        }
    }

    @Test
    fun backNavigation_fromHistoryEntry_returnsToList() {
        // First, open an email to add it to history
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(testEmlUri, "message/rfc822")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        // Open email from external intent
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
        }
        
        // Now launch the app normally (simulating opening from launcher)
        val launcherIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        
        ActivityScenario.launch<MainActivity>(launcherIntent).use { scenario ->
            // Wait for history to load
            composeTestRule.waitUntil(timeoutMillis = EMAIL_LOAD_TIMEOUT_MS) {
                try {
                    // The history entry should show the email subject
                    composeTestRule.onNodeWithText("Test Email - Simple", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Click on the history entry to open the email
            composeTestRule.onNodeWithText("Test Email - Simple", substring = true).performClick()
            
            // Wait for email detail screen to load
            composeTestRule.waitUntil(timeoutMillis = EMAIL_LOAD_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithText("From: sender@example.com", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Verify we're on the email detail screen
            composeTestRule.onNodeWithText("From: sender@example.com", substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
            
            // Press the back arrow button
            composeTestRule.onNodeWithContentDescription("Back").performClick()
            
            // Verify we're back on the home screen with the history list
            composeTestRule.waitUntil(timeoutMillis = EMAIL_LOAD_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithText("Letterbox").assertExists()
                    composeTestRule.onNodeWithText("Open file").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            composeTestRule.onNodeWithText("Letterbox").assertIsDisplayed()
            composeTestRule.onNodeWithText("Open file").assertIsDisplayed()
            
            // The history entry should still be visible
            composeTestRule.onNodeWithText("Test Email - Simple", substring = true).assertIsDisplayed()
            
            // Verify the activity is still running (didn't finish)
            val isResumed = scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            assertTrue("Activity should still be in RESUMED state", isResumed)
        }
    }

    @Test
    fun backNavigation_emailDetailScreenHasBackButton() {
        // Open an email from external intent
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
            
            // Verify the back button is present and accessible
            composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        }
    }
}
