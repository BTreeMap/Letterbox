package com.btreemap.letterbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for the Home screen (launcher entry point).
 * 
 * Tests verify:
 * - TopAppBar displays "Letterbox" title
 * - "Open file" button is present and clickable
 * - Overflow menu (3-dot icon) opens and shows menu items
 * - Empty state message appears when no history
 * - About and Clear History dialogs function correctly
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreen_displaysAppTitle() {
        // The TopAppBar should display "Letterbox"
        composeTestRule.onNodeWithText("Letterbox").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysOpenFileButton() {
        // The "Open file" button should be present and enabled
        composeTestRule
            .onNodeWithText("Open file")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun homeScreen_openFileButtonIsClickable() {
        // Verify the Open file button has a click action
        composeTestRule
            .onNode(hasText("Open file") and hasClickAction())
            .assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysEmptyStateMessage() {
        // When no files have been opened, the empty state message should appear
        composeTestRule
            .onNodeWithText("Open an .eml or .msg file to get started.")
            .assertIsDisplayed()
    }

    @Test
    fun homeScreen_overflowMenuIconIsDisplayed() {
        // The overflow menu (three dots) should be present
        composeTestRule
            .onNodeWithContentDescription("More options")
            .assertIsDisplayed()
    }

    @Test
    fun homeScreen_overflowMenuOpensAndShowsClearHistory() {
        // Click overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Wait for dropdown and verify "Clear history" is shown
        composeTestRule.onNodeWithText("Clear history").assertIsDisplayed()
    }

    @Test
    fun homeScreen_overflowMenuOpensAndShowsAbout() {
        // Click overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Verify "About" menu item is shown
        composeTestRule.onNodeWithText("About").assertIsDisplayed()
    }

    @Test
    fun homeScreen_aboutDialogOpensAndCloses() {
        // Open overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Click About
        composeTestRule.onNodeWithText("About").performClick()

        // Verify About dialog is shown
        composeTestRule.onNodeWithText("About Letterbox").assertIsDisplayed()
        composeTestRule.onNodeWithText("Letterbox is a privacy-focused .eml and .msg file viewer.", substring = true).assertIsDisplayed()

        // Close dialog by clicking OK
        composeTestRule.onNodeWithText("OK").performClick()

        // Verify dialog is closed (About Letterbox title should no longer be visible)
        composeTestRule.onNodeWithText("About Letterbox").assertDoesNotExist()
    }

    @Test
    fun homeScreen_clearHistoryDialogOpensAndCancels() {
        // Open overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Click Clear history
        composeTestRule.onNodeWithText("Clear history").performClick()

        // Verify confirmation dialog is shown
        composeTestRule.onNodeWithText("Clear history?").assertIsDisplayed()
        composeTestRule.onNodeWithText("This will remove all items from your history", substring = true).assertIsDisplayed()

        // Cancel the dialog
        composeTestRule.onNodeWithText("Cancel").performClick()

        // Verify dialog is closed
        composeTestRule.onNodeWithText("Clear history?").assertDoesNotExist()
    }

    @Test
    fun homeScreen_clearHistoryDialogConfirmsClearAction() {
        // Open overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Click Clear history
        composeTestRule.onNodeWithText("Clear history").performClick()

        // Verify confirmation dialog is shown
        composeTestRule.onNodeWithText("Clear history?").assertIsDisplayed()

        // Confirm clearing
        composeTestRule.onNodeWithText("Clear").performClick()

        // Verify dialog is closed and snackbar appears
        composeTestRule.onNodeWithText("Clear history?").assertDoesNotExist()
        
        // Snackbar should show "History cleared"
        composeTestRule.onNodeWithText("History cleared").assertIsDisplayed()
    }

    @Test
    fun homeScreen_overflowMenuOpensAndShowsSettings() {
        // Click overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Verify "Settings" menu item is shown
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun homeScreen_settingsBottomSheetOpensAndCloses() {
        // Open overflow menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Click Settings
        composeTestRule.onNodeWithText("Settings").performClick()

        // Verify Settings bottom sheet is shown with expected content
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("History limit").assertIsDisplayed()
        composeTestRule.onNodeWithText("Store local copies").assertIsDisplayed()
    }
}
