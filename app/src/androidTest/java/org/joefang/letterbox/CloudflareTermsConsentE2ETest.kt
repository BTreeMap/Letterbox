package org.joefang.letterbox

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.joefang.letterbox.data.UserPreferencesRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end tests for the Cloudflare WARP Terms of Service consent flow.
 * 
 * These tests verify:
 * - ToS dialog is shown when enabling proxy without prior consent
 * - Accepting ToS enables the proxy and saves the consent
 * - Declining ToS keeps the proxy disabled
 * - ToS dialog is not shown again after consent is given
 * - Settings correctly reflect ToS acceptance status
 */
@RunWith(AndroidJUnit4::class)
class CloudflareTermsConsentE2ETest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var preferencesRepository: UserPreferencesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferencesRepository = UserPreferencesRepository(context)
        
        // Reset preferences to defaults - no ToS acceptance, proxy disabled
        runBlocking {
            preferencesRepository.setCloudflareTermsAccepted(false)
            preferencesRepository.setEnablePrivacyProxy(false)
            preferencesRepository.setAlwaysLoadRemoteImages(false)
        }
    }

    @After
    fun tearDown() {
        // Reset preferences after tests
        runBlocking {
            preferencesRepository.setCloudflareTermsAccepted(false)
            preferencesRepository.setEnablePrivacyProxy(false)
            preferencesRepository.setAlwaysLoadRemoteImages(false)
        }
    }

    @Test
    fun enablingPrivacyProxy_withoutToSAccepted_showsTermsDialog() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Open settings menu
            composeTestRule.onNodeWithContentDescription("More options").performClick()
            composeTestRule.onNodeWithText("Settings").performClick()
            
            // Wait for settings sheet to appear
            composeTestRule.waitUntil(timeoutMillis = 3000L) {
                try {
                    composeTestRule.onNodeWithText("Use privacy proxy").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Try to enable the privacy proxy
            // First, find and click the switch for privacy proxy
            composeTestRule.onNodeWithText("Use privacy proxy").performClick()
            
            // Verify the ToS dialog appears
            composeTestRule.waitUntil(timeoutMillis = 3000L) {
                try {
                    composeTestRule.onNodeWithText("Cloudflare WARP Terms").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            composeTestRule.onNodeWithText("Cloudflare WARP Terms").assertIsDisplayed()
            composeTestRule.onNodeWithText("Accept & Enable").assertIsDisplayed()
            composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        }
    }

    @Test
    fun acceptingToS_enablesProxyAndSavesConsent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Verify initial state
            val initialConsent = runBlocking { preferencesRepository.cloudflareTermsAccepted.first() }
            assertFalse(initialConsent, "ToS should not be accepted initially")
            
            // Open settings and trigger ToS dialog
            composeTestRule.onNodeWithContentDescription("More options").performClick()
            composeTestRule.onNodeWithText("Settings").performClick()
            
            composeTestRule.waitUntil(timeoutMillis = 3000L) {
                try {
                    composeTestRule.onNodeWithText("Use privacy proxy").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            composeTestRule.onNodeWithText("Use privacy proxy").performClick()
            
            // Wait for ToS dialog
            composeTestRule.waitUntil(timeoutMillis = 3000L) {
                try {
                    composeTestRule.onNodeWithText("Accept & Enable").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Accept the terms
            composeTestRule.onNodeWithText("Accept & Enable").performClick()
            
            // Wait a moment for preferences to be saved
            Thread.sleep(500)
            
            // Verify consent is saved
            val consentAfterAccept = runBlocking { preferencesRepository.cloudflareTermsAccepted.first() }
            assertTrue(consentAfterAccept, "ToS should be accepted after clicking Accept")
            
            // Verify proxy is enabled
            val proxyEnabled = runBlocking { preferencesRepository.enablePrivacyProxy.first() }
            assertTrue(proxyEnabled, "Privacy proxy should be enabled after accepting ToS")
        }
    }

    @Test
    fun decliningToS_keepsProxyDisabledAndNoConsent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Verify initial state
            val initialConsent = runBlocking { preferencesRepository.cloudflareTermsAccepted.first() }
            assertFalse(initialConsent, "ToS should not be accepted initially")
            
            // Open settings and trigger ToS dialog
            composeTestRule.onNodeWithContentDescription("More options").performClick()
            composeTestRule.onNodeWithText("Settings").performClick()
            
            composeTestRule.waitUntil(timeoutMillis = 3000L) {
                try {
                    composeTestRule.onNodeWithText("Use privacy proxy").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            composeTestRule.onNodeWithText("Use privacy proxy").performClick()
            
            // Wait for ToS dialog
            composeTestRule.waitUntil(timeoutMillis = 3000L) {
                try {
                    composeTestRule.onNodeWithText("Cancel").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Decline the terms
            composeTestRule.onNodeWithText("Cancel").performClick()
            
            // Wait a moment for any state changes
            Thread.sleep(500)
            
            // Verify consent is NOT saved
            val consentAfterDecline = runBlocking { preferencesRepository.cloudflareTermsAccepted.first() }
            assertFalse(consentAfterDecline, "ToS should not be accepted after clicking Cancel")
            
            // Verify proxy is NOT enabled
            val proxyEnabled = runBlocking { preferencesRepository.enablePrivacyProxy.first() }
            assertFalse(proxyEnabled, "Privacy proxy should remain disabled after declining ToS")
        }
    }

    @Test
    fun toSAlreadyAccepted_noDialogShownWhenEnablingProxy() {
        // Pre-accept the ToS
        runBlocking {
            preferencesRepository.setCloudflareTermsAccepted(true)
            preferencesRepository.setEnablePrivacyProxy(false)
        }
        
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Open settings
            composeTestRule.onNodeWithContentDescription("More options").performClick()
            composeTestRule.onNodeWithText("Settings").performClick()
            
            composeTestRule.waitUntil(timeoutMillis = 3000L) {
                try {
                    composeTestRule.onNodeWithText("Use privacy proxy").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Enable the privacy proxy
            composeTestRule.onNodeWithText("Use privacy proxy").performClick()
            
            // Wait a moment
            Thread.sleep(500)
            
            // Verify NO ToS dialog appears
            composeTestRule.onNodeWithText("Cloudflare WARP Terms").assertDoesNotExist()
            
            // Verify proxy is now enabled (direct toggle without dialog)
            val proxyEnabled = runBlocking { preferencesRepository.enablePrivacyProxy.first() }
            assertTrue(proxyEnabled, "Privacy proxy should be enabled directly when ToS already accepted")
        }
    }

    @Test
    fun tosDialogContainsLinkToCloudflareTerms() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Open settings and trigger ToS dialog
            composeTestRule.onNodeWithContentDescription("More options").performClick()
            composeTestRule.onNodeWithText("Settings").performClick()
            
            composeTestRule.waitUntil(timeoutMillis = 3000L) {
                try {
                    composeTestRule.onNodeWithText("Use privacy proxy").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            composeTestRule.onNodeWithText("Use privacy proxy").performClick()
            
            // Wait for ToS dialog
            composeTestRule.waitUntil(timeoutMillis = 3000L) {
                try {
                    composeTestRule.onNodeWithText("Cloudflare WARP Terms").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Verify the dialog contains the link to view terms
            composeTestRule.onNodeWithText("View Cloudflare Terms of Service").assertIsDisplayed()
        }
    }

    @Test
    fun disablingProxy_afterToSAccepted_noDialogShown() {
        // Pre-accept ToS and enable proxy
        runBlocking {
            preferencesRepository.setCloudflareTermsAccepted(true)
            preferencesRepository.setEnablePrivacyProxy(true)
        }
        
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Open settings
            composeTestRule.onNodeWithContentDescription("More options").performClick()
            composeTestRule.onNodeWithText("Settings").performClick()
            
            composeTestRule.waitUntil(timeoutMillis = 3000L) {
                try {
                    composeTestRule.onNodeWithText("Use privacy proxy").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Disable the privacy proxy
            composeTestRule.onNodeWithText("Use privacy proxy").performClick()
            
            // Wait a moment
            Thread.sleep(500)
            
            // Verify NO ToS dialog appears when disabling
            composeTestRule.onNodeWithText("Cloudflare WARP Terms").assertDoesNotExist()
            
            // Verify proxy is now disabled
            val proxyEnabled = runBlocking { preferencesRepository.enablePrivacyProxy.first() }
            assertFalse(proxyEnabled, "Privacy proxy should be disabled")
            
            // Consent should still be saved
            val consentStillSaved = runBlocking { preferencesRepository.cloudflareTermsAccepted.first() }
            assertTrue(consentStillSaved, "ToS consent should remain after disabling proxy")
        }
    }
}
