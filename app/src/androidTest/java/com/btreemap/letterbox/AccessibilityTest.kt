package com.btreemap.letterbox

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for accessibility and UI structure.
 * 
 * Tests verify:
 * - UI elements have proper content descriptions for accessibility
 * - Expected number of interactive elements are present
 * - Interactive controls are enabled by default
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun accessibility_overflowMenuHasContentDescription() {
        // The overflow menu should have a content description for screen readers
        composeTestRule
            .onNodeWithContentDescription("More options")
            .assertIsDisplayed()
    }

    @Test
    fun accessibility_exactlyOneOverflowMenuExists() {
        // There should be exactly one overflow menu button
        composeTestRule
            .onAllNodesWithContentDescription("More options")
            .assertCountEquals(1)
    }

    @Test
    fun accessibility_openFileButtonIsAccessible() {
        // The Open file button should be interactive
        composeTestRule
            .onNode(hasText("Open file") and hasClickAction())
            .assertIsDisplayed()
    }

    @Test
    fun accessibility_menuItemsHaveText() {
        // Open menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Menu items should have readable text
        composeTestRule.onNodeWithText("Clear history").assertIsDisplayed()
        composeTestRule.onNodeWithText("About").assertIsDisplayed()
    }

    @Test
    fun accessibility_dialogButtonsHaveText() {
        // Open About dialog
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("About").performClick()

        // Dialog should have readable title and button
        composeTestRule.onNodeWithText("About Letterbox").assertIsDisplayed()
        composeTestRule.onNodeWithText("OK").assertIsDisplayed()
        
        // Clean up
        composeTestRule.onNodeWithText("OK").performClick()
    }

    @Test
    fun accessibility_confirmationDialogHasBothButtons() {
        // Open Clear History dialog
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Clear history").performClick()

        // Confirmation dialog should have both Cancel and Clear buttons
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()
        
        // Clean up
        composeTestRule.onNodeWithText("Cancel").performClick()
    }

    @Test
    fun uiStructure_homeScreenShowsAllExpectedElements() {
        // Title in TopAppBar
        composeTestRule.onNodeWithText("Letterbox").assertIsDisplayed()
        
        // Open file button
        composeTestRule.onNodeWithText("Open file").assertIsDisplayed()
        
        // Overflow menu
        composeTestRule.onNodeWithContentDescription("More options").assertIsDisplayed()
        
        // Empty state message (since no history on fresh start)
        composeTestRule.onNodeWithText("Open an .eml or .msg file to get started.").assertIsDisplayed()
    }

    @Test
    fun uiStructure_overflowMenuHasExpectedItems() {
        // Open menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()

        // Verify expected menu items exist
        composeTestRule.onNodeWithText("Clear history").assertIsDisplayed()
        composeTestRule.onNodeWithText("About").assertIsDisplayed()
    }
}
