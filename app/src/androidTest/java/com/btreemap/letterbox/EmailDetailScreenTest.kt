package com.btreemap.letterbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btreemap.letterbox.ui.AttachmentData
import com.btreemap.letterbox.ui.EmailContent
import com.btreemap.letterbox.ui.EmailDetailScreen
import com.btreemap.letterbox.ui.theme.LetterboxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for the EmailDetailScreen.
 * 
 * Tests verify:
 * - Email header displays From, To, Cc, Date
 * - Details dialog shows extended information
 * - Attachments section is displayed and expandable
 * - Overflow menu contains expected actions
 */
@RunWith(AndroidJUnit4::class)
class EmailDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleEmail = EmailContent(
        subject = "Test Subject",
        from = "sender@example.com",
        to = "recipient@example.com",
        cc = "cc@example.com",
        replyTo = "reply@example.com",
        messageId = "msg123@example.com",
        date = "2025-12-12T10:00:00Z",
        bodyHtml = "<p>Test body</p>",
        attachments = listOf(
            AttachmentData(
                name = "test.pdf",
                contentType = "application/pdf",
                size = 1024,
                index = 0
            )
        ),
        getResource = { null },
        getAttachmentContent = { null }
    )

    @Test
    fun emailDetailScreen_displaysSubjectInTitle() {
        composeTestRule.setContent {
            LetterboxTheme {
                EmailDetailScreen(
                    email = sampleEmail,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Test Subject").assertIsDisplayed()
    }

    @Test
    fun emailDetailScreen_displaysHeader() {
        composeTestRule.setContent {
            LetterboxTheme {
                EmailDetailScreen(
                    email = sampleEmail,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("From: sender@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("To: recipient@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cc: cc@example.com").assertIsDisplayed()
    }

    @Test
    fun emailDetailScreen_displaysAttachmentsSection() {
        composeTestRule.setContent {
            LetterboxTheme {
                EmailDetailScreen(
                    email = sampleEmail,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Attachments (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("test.pdf").assertIsDisplayed()
    }

    @Test
    fun emailDetailScreen_overflowMenuShowsDetails() {
        composeTestRule.setContent {
            LetterboxTheme {
                EmailDetailScreen(
                    email = sampleEmail,
                    onNavigateBack = {}
                )
            }
        }

        // Open overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Verify Details menu item is shown
        composeTestRule.onNodeWithText("Details").assertIsDisplayed()
    }

    @Test
    fun emailDetailScreen_detailsDialogShowsExtendedInfo() {
        composeTestRule.setContent {
            LetterboxTheme {
                EmailDetailScreen(
                    email = sampleEmail,
                    onNavigateBack = {}
                )
            }
        }

        // Open overflow menu and click Details
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Details").performClick()

        // Verify extended details are shown
        composeTestRule.onNodeWithText("Email Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Subject").assertIsDisplayed()
        composeTestRule.onNodeWithText("cc@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("reply@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("msg123@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("Attachments (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("• test.pdf (1 KB)", substring = true).assertIsDisplayed()
    }

    @Test
    fun emailDetailScreen_backButtonExists() {
        composeTestRule.setContent {
            LetterboxTheme {
                EmailDetailScreen(
                    email = sampleEmail,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun emailDetailScreen_shareEmlMenuItemShownWhenCallbackProvided() {
        composeTestRule.setContent {
            LetterboxTheme {
                EmailDetailScreen(
                    email = sampleEmail,
                    onNavigateBack = {},
                    onShareEml = {}
                )
            }
        }

        // Open overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Verify Share .eml menu item is shown
        composeTestRule.onNodeWithText("Share .eml").assertIsDisplayed()
    }

    @Test
    fun emailDetailScreen_removeFromHistoryMenuItemShownWhenCallbackProvided() {
        composeTestRule.setContent {
            LetterboxTheme {
                EmailDetailScreen(
                    email = sampleEmail,
                    onNavigateBack = {},
                    onRemoveFromHistory = {}
                )
            }
        }

        // Open overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Verify Remove from history menu item is shown
        composeTestRule.onNodeWithText("Remove from history").assertIsDisplayed()
    }
}
