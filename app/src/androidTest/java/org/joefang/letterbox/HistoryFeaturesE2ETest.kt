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

    // Test data.
    //
    // `displayName` mirrors `subject` to match production ingestion, where
    // `EmailViewModel.ingestFromUri` stores the parsed subject as the display
    // name (falling back to the filename only when the subject is blank). The
    // history list renders `displayName`, so the on-screen text the assertions
    // look for is the subject.
    private val email1 = HistoryItemEntity(
        id = 1,
        blobHash = "hash1",
        displayName = "Invoice from Amazon",
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
        displayName = "Meeting with Team",
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
        displayName = "Weekly Newsletter",
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
            // Mark onboarding complete so the main UI is reachable.
            TestPreferences.seedOnboarded(context)

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
        // Open overflow menu and the settings sheet.
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Settings").performClick()

        // The Storage section starts out reporting the three seeded emails.
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithText("3 emails", substring = true).assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Click Clear in the Storage section.
        composeTestRule.onNodeWithText("Clear").performClick()

        // Confirm dialog. Both the settings button and the dialog button read
        // "Clear", so target the clickable node that is currently on top.
        composeTestRule.onNodeWithText("Clear cache?").assertIsDisplayed()
        composeTestRule.onNode(hasText("Clear") and androidx.compose.ui.test.hasClickAction()).performClick()

        // Verify the cache is empty. The Storage section reflects the database
        // count, so "No cached emails" confirms every history item was removed.
        // We assert from within the still-open settings sheet rather than
        // dismissing it: the ModalBottomSheet is hosted in its own window, and
        // pressing back (via either the activity dispatcher or Espresso) finishes
        // the activity instead of closing the sheet, destroying the Compose tree.
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithText("No cached emails").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        composeTestRule.onNodeWithText("No cached emails").assertIsDisplayed()
    }
}
