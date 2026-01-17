package org.joefang.letterbox

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.joefang.letterbox.data.UserPreferencesRepository
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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

    companion object {
        private const val SETTINGS_TIMEOUT_MS = 5000L
        private const val DIALOG_TIMEOUT_MS = 3000L
    }

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
            
            // Wait for settings sheet to appear and find the privacy proxy switch
            composeTestRule.waitUntil(timeoutMillis = SETTINGS_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithTag("privacyProxySwitch").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Click the privacy proxy switch to enable it
            composeTestRule.onNodeWithTag("privacyProxySwitch").performClick()
            
            // Verify the ToS dialog appears
            composeTestRule.waitUntil(timeoutMillis = DIALOG_TIMEOUT_MS) {
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
            assertFalse("ToS should not be accepted initially", initialConsent)
            
            // Open settings and trigger ToS dialog
            composeTestRule.onNodeWithContentDescription("More options").performClick()
            composeTestRule.onNodeWithText("Settings").performClick()
            
            composeTestRule.waitUntil(timeoutMillis = SETTINGS_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithTag("privacyProxySwitch").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            composeTestRule.onNodeWithTag("privacyProxySwitch").performClick()
            
            // Wait for ToS dialog
            composeTestRule.waitUntil(timeoutMillis = DIALOG_TIMEOUT_MS) {
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
            Thread.sleep(1000)
            
            // Verify consent is saved
            val consentAfterAccept = runBlocking { preferencesRepository.cloudflareTermsAccepted.first() }
            assertTrue("ToS should be accepted after clicking Accept", consentAfterAccept)
            
            // Verify proxy is enabled
            val proxyEnabled = runBlocking { preferencesRepository.enablePrivacyProxy.first() }
            assertTrue("Privacy proxy should be enabled after accepting ToS", proxyEnabled)
        }
    }

    @Test
    fun decliningToS_keepsProxyDisabledAndNoConsent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Verify initial state
            val initialConsent = runBlocking { preferencesRepository.cloudflareTermsAccepted.first() }
            assertFalse("ToS should not be accepted initially", initialConsent)
            
            // Open settings and trigger ToS dialog
            composeTestRule.onNodeWithContentDescription("More options").performClick()
            composeTestRule.onNodeWithText("Settings").performClick()
            
            composeTestRule.waitUntil(timeoutMillis = SETTINGS_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithTag("privacyProxySwitch").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            composeTestRule.onNodeWithTag("privacyProxySwitch").performClick()
            
            // Wait for ToS dialog
            composeTestRule.waitUntil(timeoutMillis = DIALOG_TIMEOUT_MS) {
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
            Thread.sleep(1000)
            
            // Verify consent is NOT saved
            val consentAfterDecline = runBlocking { preferencesRepository.cloudflareTermsAccepted.first() }
            assertFalse("ToS should not be accepted after clicking Cancel", consentAfterDecline)
            
            // Verify proxy is NOT enabled
            val proxyEnabled = runBlocking { preferencesRepository.enablePrivacyProxy.first() }
            assertFalse("Privacy proxy should remain disabled after declining ToS", proxyEnabled)
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
            
            composeTestRule.waitUntil(timeoutMillis = SETTINGS_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithTag("privacyProxySwitch").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Wait for the DataStore to emit the cloudflareTermsAccepted value
            // This ensures the collectAsState has received the actual value, not just the initial=false
            composeTestRule.waitUntil(timeoutMillis = SETTINGS_TIMEOUT_MS) {
                val termsAccepted = runBlocking { preferencesRepository.cloudflareTermsAccepted.first() }
                termsAccepted == true
            }
            
            // Additional wait for compose to recompose with the new state
            composeTestRule.waitForIdle()
            
            // Enable the privacy proxy by clicking the switch
            composeTestRule.onNodeWithTag("privacyProxySwitch").performClick()
            
            // Wait for preferences to be saved
            composeTestRule.waitForIdle()
            Thread.sleep(500)
            
            // Verify NO ToS dialog appears
            composeTestRule.onNodeWithText("Cloudflare WARP Terms").assertDoesNotExist()
            
            // Verify proxy is now enabled (direct toggle without dialog)
            val proxyEnabled = runBlocking { preferencesRepository.enablePrivacyProxy.first() }
            assertTrue("Privacy proxy should be enabled directly when ToS already accepted", proxyEnabled)
        }
    }

    @Test
    fun tosDialogContainsLinkToCloudflareTerms() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Open settings and trigger ToS dialog
            composeTestRule.onNodeWithContentDescription("More options").performClick()
            composeTestRule.onNodeWithText("Settings").performClick()
            
            composeTestRule.waitUntil(timeoutMillis = SETTINGS_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithTag("privacyProxySwitch").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            composeTestRule.onNodeWithTag("privacyProxySwitch").performClick()
            
            // Wait for ToS dialog
            composeTestRule.waitUntil(timeoutMillis = DIALOG_TIMEOUT_MS) {
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
            
            composeTestRule.waitUntil(timeoutMillis = SETTINGS_TIMEOUT_MS) {
                try {
                    composeTestRule.onNodeWithTag("privacyProxySwitch").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Disable the privacy proxy by clicking the switch
            composeTestRule.onNodeWithTag("privacyProxySwitch").performClick()
            
            // Wait for preferences to be saved
            Thread.sleep(1000)
            
            // Verify NO ToS dialog appears when disabling
            composeTestRule.onNodeWithText("Cloudflare WARP Terms").assertDoesNotExist()
            
            // Verify proxy is now disabled
            val proxyEnabled = runBlocking { preferencesRepository.enablePrivacyProxy.first() }
            assertFalse("Privacy proxy should be disabled", proxyEnabled)
            
            // Consent should still be saved
            val consentStillSaved = runBlocking { preferencesRepository.cloudflareTermsAccepted.first() }
            assertTrue("ToS consent should remain after disabling proxy", consentStillSaved)
        }
    }
}
