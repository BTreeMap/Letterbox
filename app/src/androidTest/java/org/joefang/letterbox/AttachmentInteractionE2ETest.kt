package org.joefang.letterbox

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.compose.ui.test.hasText

/**
 * End-to-end tests for attachment interactions and sharing.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentInteractionE2ETest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var testEmlFile: File
    private lateinit var testEmlUri: android.net.Uri

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        Intents.init()

        // Create a simple EML with an attachment
        val boundary = "boundary123"
        val emlWithAttachment = """From: sender@example.com
To: recipient@example.com
Subject: Test with Attachment
Date: Mon, 22 Dec 2025 10:00:00 +0000
Content-Type: multipart/mixed; boundary="$boundary"

--$boundary
Content-Type: text/plain; charset=UTF-8

This is the body.

--$boundary
Content-Type: text/plain; name="note.txt"
Content-Disposition: attachment; filename="note.txt"
Content-Transfer-Encoding: base64

SGVsbG8gV29ybGQ=
--$boundary--
"""

        // Create test file in the "shared" subdirectory of cache
        val sharedDir = File(context.cacheDir, "shared")
        sharedDir.mkdirs()
        testEmlFile = File(sharedDir, "test_attachment.eml")
        testEmlFile.writeText(emlWithAttachment)

        // Create content URI
        testEmlUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            testEmlFile
        )
    }

    @After
    fun tearDown() {
        Intents.release()
        testEmlFile.delete()
    }

    @Test
    fun openAttachment_launchesActionView() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(testEmlUri, "message/rfc822")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // Wait for email to load
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                try {
                    composeTestRule.onNodeWithText("Test with Attachment").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }

            // Wait for Compose to settle
            composeTestRule.waitForIdle()

            // Verify attachment section is visible
            composeTestRule.onNodeWithText("Attachments (1)").assertIsDisplayed()

            // Expand attachments if needed (should be expanded by default if non-empty)
            // But verify the file name is visible
            composeTestRule.onNodeWithText("note.txt", substring = true).assertIsDisplayed()

            // Click on attachment
            composeTestRule.onNodeWithText("note.txt", substring = true).performClick()

            // Verify Intent
            Intents.intended(
                Matchers.allOf(
                    IntentMatchers.hasAction(Intent.ACTION_CHOOSER),
                    IntentMatchers.hasExtra(
                        Matchers.equalTo(Intent.EXTRA_INTENT),
                        Matchers.allOf(
                            IntentMatchers.hasAction(Intent.ACTION_VIEW),
                            IntentMatchers.hasType("text/plain")
                        )
                    )
                )
            )
        }
    }

    @Test
    fun shareEml_launchesActionSend() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(testEmlUri, "message/rfc822")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // Wait for email to load
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                try {
                    composeTestRule.onNodeWithText("Test with Attachment").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }

            // Wait for Compose to settle
            composeTestRule.waitForIdle()

            // Open overflow menu
            composeTestRule.onNodeWithContentDescription("More options").performClick()

            // Click Share .eml
            composeTestRule.onNodeWithText("Share .eml").performClick()

            // Verify Intent
            Intents.intended(
                Matchers.allOf(
                    IntentMatchers.hasAction(Intent.ACTION_CHOOSER),
                    IntentMatchers.hasExtra(
                        Matchers.equalTo(Intent.EXTRA_INTENT),
                        Matchers.allOf(
                            IntentMatchers.hasAction(Intent.ACTION_SEND),
                            IntentMatchers.hasType("message/rfc822")
                        )
                    )
                )
            )
        }
    }
}
