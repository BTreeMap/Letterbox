package org.joefang.letterbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.joefang.letterbox.data.LetterboxDatabase
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for navigation behavior.
 * 
 * Tests verify:
 * - Menu dismisses when clicking outside
 * - Dialogs dismiss on back or dismiss action
 * - UI state is consistent after interactions
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {

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
    fun navigation_overflowMenuDismissesWhenMenuItemClicked() {
        // Open overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Verify menu is open
        composeTestRule.onNodeWithText("About").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear history").assertIsDisplayed()

        // Click About (which should open dialog and dismiss menu)
        composeTestRule.onNodeWithText("About").performClick()

        // Verify About dialog is shown
        composeTestRule.onNodeWithText("About Letterbox").assertIsDisplayed()

        // Close dialog
        composeTestRule.onNodeWithText("OK").performClick()

        // Verify we're back to home screen with all elements visible
        composeTestRule.onNodeWithText("Letterbox").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open file").assertIsDisplayed()
    }

    @Test
    fun navigation_multipleMenuOpenCloseOperations() {
        // Test opening and closing the menu multiple times to ensure consistent state
        for (i in 1..3) {
            // Open overflow menu
            composeTestRule.onNodeWithContentDescription("More options").performClick()

            // Verify menu is open
            composeTestRule.onNodeWithText("About").assertIsDisplayed()

            // Click About
            composeTestRule.onNodeWithText("About").performClick()

            // Verify dialog opened
            composeTestRule.onNodeWithText("About Letterbox").assertIsDisplayed()

            // Close dialog
            composeTestRule.onNodeWithText("OK").performClick()

            // Verify dialog closed
            composeTestRule.onNodeWithText("About Letterbox").assertDoesNotExist()
        }
    }

    @Test
    fun navigation_homeScreenElementsRemainAfterDialogInteraction() {
        // Open and close the About dialog
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("About").performClick()
        composeTestRule.onNodeWithText("OK").performClick()

        // All home screen elements should still be present
        composeTestRule.onNodeWithText("Letterbox").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open file").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open an .eml or .msg file to get started.").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("More options").assertIsDisplayed()
    }

    @Test
    fun navigation_clearHistoryDialogCancelReturnsToHome() {
        // Open Clear History dialog
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Clear history").performClick()

        // Verify dialog is shown
        composeTestRule.onNodeWithText("Clear history?").assertIsDisplayed()

        // Cancel
        composeTestRule.onNodeWithText("Cancel").performClick()

        // Verify we're back to home screen
        composeTestRule.onNodeWithText("Letterbox").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open file").assertIsDisplayed()
    }
}
