package org.joefang.letterbox

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.joefang.letterbox.data.BlobEntity
import org.joefang.letterbox.data.HistoryItemEntity
import org.joefang.letterbox.data.LetterboxDatabase
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end tests for history list features including search, filter, sort, and delete.
 */
@RunWith(AndroidJUnit4::class)
class HistoryFeaturesE2ETest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var database: LetterboxDatabase
    private lateinit var scenario: ActivityScenario<MainActivity>

    // Test data
    private val email1 = HistoryItemEntity(
        id = 1,
        blobHash = "hash1",
        displayName = "Invoice",
        originalUri = "content://1",
        lastAccessed = 1000,
        subject = "Invoice from Amazon",
        senderEmail = "amazon@example.com",
        senderName = "Amazon",
        emailDate = 1700000000000L, // 2023-11-14
        hasAttachments = true,
        bodyPreview = "Your order has shipped"
    )

    private val email2 = HistoryItemEntity(
        id = 2,
        blobHash = "hash2",
        displayName = "Meeting",
        originalUri = "content://2",
        lastAccessed = 2000,
        subject = "Meeting with Team",
        senderEmail = "boss@example.com",
        senderName = "The Boss",
        emailDate = 1701000000000L, // 2023-11-26
        hasAttachments = false,
        bodyPreview = "Let's meet on Monday"
    )

    private val email3 = HistoryItemEntity(
        id = 3,
        blobHash = "hash3",
        displayName = "Newsletter",
        originalUri = "content://3",
        lastAccessed = 3000,
        subject = "Weekly Newsletter",
        senderEmail = "news@example.com",
        senderName = "Tech News",
        emailDate = 1699000000000L, // 2023-11-03
        hasAttachments = false,
        bodyPreview = "Here are the top stories"
    )

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = LetterboxDatabase.getInstance(context)

        runBlocking {
            // Clear existing data
            database.historyItemDao().deleteAll()

            // Insert test data
            // We need corresponding blobs for foreign key constraint
            database.blobDao().insert(BlobEntity("hash1", 100, 1))
            database.blobDao().insert(BlobEntity("hash2", 200, 1))
            database.blobDao().insert(BlobEntity("hash3", 300, 1))

            // We also need actual files for the blobs if the app tries to load them,
            // but for list view testing it might not be strictly necessary unless the VM verifies them.
            // Let's create dummy files just in case.
            val casDir = File(context.filesDir, "cas")
            casDir.mkdirs()
            File(casDir, "hash1").writeText("dummy content 1")
            File(casDir, "hash2").writeText("dummy content 2")
            File(casDir, "hash3").writeText("dummy content 3")

            database.historyItemDao().insert(email1)
            database.historyItemDao().insert(email2)
            database.historyItemDao().insert(email3)
        }

        // Launch activity after data is pre-populated
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        scenario = ActivityScenario.launch(intent)
    }

    @After
    fun tearDown() {
        scenario.close()
        runBlocking {
            database.historyItemDao().deleteAll()
            database.blobDao().deleteByHash("hash1")
            database.blobDao().deleteByHash("hash2")
            database.blobDao().deleteByHash("hash3")
        }
    }

    @Test
    fun search_filtersListBySubject() {
        // Activate search
        composeTestRule.onNodeWithContentDescription("Search emails").performClick()

        // Type search query
        composeTestRule.onNodeWithTag("searchTextField").performTextInput("Amazon")

        // Verify only matching email is shown
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithText("Invoice from Amazon").assertExists()
                composeTestRule.onNodeWithText("Meeting with Team").assertDoesNotExist()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        composeTestRule.onNodeWithText("Invoice from Amazon").assertIsDisplayed()
        composeTestRule.onNodeWithText("Meeting with Team").assertDoesNotExist()
        composeTestRule.onNodeWithText("Weekly Newsletter").assertDoesNotExist()
    }

    @Test
    fun filter_togglesAttachmentsOnly() {
        // Click "Has attachments" filter
        composeTestRule.onNodeWithContentDescription("Filter by attachments").performClick()

        // Verify only email with attachments is shown
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithText("Invoice from Amazon").assertExists()
                composeTestRule.onNodeWithText("Meeting with Team").assertDoesNotExist()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        composeTestRule.onNodeWithText("Invoice from Amazon").assertIsDisplayed()
        composeTestRule.onNodeWithText("Meeting with Team").assertDoesNotExist()
        composeTestRule.onNodeWithText("Weekly Newsletter").assertDoesNotExist()

        // Toggle off
        composeTestRule.onNodeWithContentDescription("Filter: showing only emails with attachments").performClick()

        // Verify all emails shown again
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithText("Meeting with Team").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        composeTestRule.onNodeWithText("Invoice from Amazon").assertIsDisplayed()
        composeTestRule.onNodeWithText("Meeting with Team").assertIsDisplayed()
        composeTestRule.onNodeWithText("Weekly Newsletter").assertIsDisplayed()
    }

    @Test
    fun delete_removesItemFromList() {
        // Isolate the item to be deleted using search to ensure we click the correct delete button
        composeTestRule.onNodeWithContentDescription("Search emails").performClick()
        composeTestRule.onNodeWithTag("searchTextField").performTextInput("Meeting")

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithText("Meeting with Team").assertExists()
                composeTestRule.onNodeWithText("Invoice from Amazon").assertDoesNotExist()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Now delete the visible item
        composeTestRule.onNodeWithContentDescription("Delete email").performClick()

        // Verify it's gone
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithText("Meeting with Team").assertDoesNotExist()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Clear search
        composeTestRule.onNodeWithContentDescription("Clear search").performClick()
        composeTestRule.onNodeWithContentDescription("Close search").performClick()

        // Verify other items remain
        composeTestRule.onNodeWithText("Invoice from Amazon").assertIsDisplayed()
        composeTestRule.onNodeWithText("Weekly Newsletter").assertIsDisplayed()
        composeTestRule.onNodeWithText("Meeting with Team").assertDoesNotExist()
    }

    @Test
    fun clearCache_removesAllItems() {
        // Open overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Settings").performClick()

        // Click Clear in Storage section
        composeTestRule.onNodeWithText("Clear").performClick()

        // Confirm dialog
        composeTestRule.onNodeWithText("Clear cache?").assertIsDisplayed()
        // There are two "Clear" texts now (button in settings and button in dialog)
        // We need to click the one in the dialog that is clickable
        // The one in settings is behind the dialog and shouldn't be clickable, but to be safe use strict matching
        // or rely on dialog hierarchy traversal if possible.
        // Simple way: match text "Clear" and ensure it's a button (has click action) and perform click.
        // Since both have click action, we might hit the wrong one if not careful.
        // However, the dialog is on top.
        composeTestRule.onNode(hasText("Clear") and androidx.compose.ui.test.hasClickAction()).performClick()

        // Dismiss settings sheet by pressing back
        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        // Verify empty state
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithText("Open an .eml or .msg file to get started.").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        composeTestRule.onNodeWithText("Invoice from Amazon").assertDoesNotExist()
        composeTestRule.onNodeWithText("Meeting with Team").assertDoesNotExist()
    }
}
