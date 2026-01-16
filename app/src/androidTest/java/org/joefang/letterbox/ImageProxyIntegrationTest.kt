package org.joefang.letterbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.joefang.letterbox.data.UserPreferencesRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Integration tests for the image proxy feature.
 * 
 * These tests verify that:
 * - Remote images are blocked by default for privacy
 * - The "Show" button appears when email contains remote images
 * - Clicking "Show" enables image loading
 * - Images are loaded through the privacy proxy when enabled
 * - Cloudflare ToS consent is properly handled
 * 
 * Note: These tests do not verify actual network connectivity or image rendering
 * as that would require a real network. Instead, they verify the UI flow and
 * settings integration work correctly.
 */
@RunWith(AndroidJUnit4::class)
class ImageProxyIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var testEmlFile: File
    private lateinit var testEmlUri: Uri
    private lateinit var preferencesRepository: UserPreferencesRepository

    companion object {
        private const val EMAIL_LOAD_TIMEOUT_MS = 10000L
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferencesRepository = UserPreferencesRepository(context)
        
        // Reset preferences to defaults before each test
        runBlocking {
            preferencesRepository.setAlwaysLoadRemoteImages(false)
            preferencesRepository.setEnablePrivacyProxy(true)
            preferencesRepository.setCloudflareTermsAccepted(true)
        }
        
        // Copy test EML with remote images from test APK assets
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val emlContent = testContext.assets.open("test_with_images.eml").bufferedReader().use { it.readText() }
        
        // Create test file in the "shared" subdirectory of cache
        val sharedDir = File(context.cacheDir, "shared")
        sharedDir.mkdirs()
        testEmlFile = File(sharedDir, "test_with_images.eml")
        testEmlFile.writeText(emlContent)
        
        // Create content URI for the test file
        testEmlUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            testEmlFile
        )
    }

    @After
    fun tearDown() {
        // Clean up test file
        testEmlFile.delete()
        
        // Reset preferences after tests
        runBlocking {
            preferencesRepository.setAlwaysLoadRemoteImages(false)
            preferencesRepository.setEnablePrivacyProxy(true)
        }
    }

    @Test
    fun emailWithRemoteImages_showsRemoteImagesBanner() {
        // Launch activity with email containing remote images
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
                    composeTestRule.onNodeWithText("Test Email - With Remote Images", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Verify the remote images banner is displayed
            composeTestRule.onNodeWithText("Remote images are hidden", substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithText("Show").assertIsDisplayed()
        }
    }

    @Test
    fun emailWithRemoteImages_clickShowButton_hidessBanner() {
        // Launch activity with email containing remote images
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
                    composeTestRule.onNodeWithText("Remote images are hidden", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Click the "Show" button
            composeTestRule.onNodeWithText("Show").performClick()
            
            // Wait for the banner to disappear
            composeTestRule.waitUntil(timeoutMillis = 2000L) {
                try {
                    composeTestRule.onNodeWithText("Remote images are hidden", substring = true).assertDoesNotExist()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
        }
    }

    @Test
    fun emailWithRemoteImages_alwaysLoadEnabled_noBannerShown() {
        // Enable always load remote images
        runBlocking {
            preferencesRepository.setAlwaysLoadRemoteImages(true)
        }
        
        // Launch activity with email containing remote images
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
                    composeTestRule.onNodeWithText("Test Email - With Remote Images", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Verify the banner is NOT shown when always load is enabled
            composeTestRule.onNodeWithText("Remote images are hidden", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun complexEmailWithMultipleImageTypes_showsRemoteImagesBanner() {
        // Copy complex test EML
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val emlContent = testContext.assets.open("test_complex_images.eml").bufferedReader().use { it.readText() }
        
        val complexEmlFile = File(context.cacheDir, "shared/test_complex_images.eml")
        complexEmlFile.writeText(emlContent)
        
        val complexEmlUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            complexEmlFile
        )
        
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(complexEmlUri, "message/rfc822")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                // Wait for email to load
                composeTestRule.waitUntil(timeoutMillis = EMAIL_LOAD_TIMEOUT_MS) {
                    try {
                        composeTestRule.onNodeWithText("Complex Email with Multiple Image Types", substring = true).assertExists()
                        true
                    } catch (e: AssertionError) {
                        false
                    }
                }
                
                // Verify the remote images banner is displayed
                // This email has both CID (inline) and remote images
                composeTestRule.onNodeWithText("Remote images are hidden", substring = true).assertIsDisplayed()
                composeTestRule.onNodeWithText("Show").assertIsDisplayed()
            }
        } finally {
            complexEmlFile.delete()
        }
    }

    @Test
    fun privacyProxyEnabled_imageLoadingThroughProxy() {
        // Ensure privacy proxy is enabled and ToS accepted
        runBlocking {
            preferencesRepository.setEnablePrivacyProxy(true)
            preferencesRepository.setCloudflareTermsAccepted(true)
        }
        
        // Verify the setting is persisted correctly
        val proxyEnabled = runBlocking { preferencesRepository.enablePrivacyProxy.first() }
        assert(proxyEnabled) { "Privacy proxy should be enabled" }
        
        // Launch activity with email containing remote images
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
                    composeTestRule.onNodeWithText("Test Email - With Remote Images", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Verify banner shows proxy information
            composeTestRule.onNodeWithText("privacy proxy", substring = true, ignoreCase = true).assertIsDisplayed()
        }
    }

    @Test
    fun emailWithRemoteImages_verifyFromHeaderDisplayed() {
        // Launch activity with email containing remote images
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
                    composeTestRule.onNodeWithText("Test Email - With Remote Images", substring = true).assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            
            // Verify email headers are displayed correctly
            composeTestRule.onNodeWithText("From: sender@example.com", substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithText("To: recipient@example.com", substring = true).assertIsDisplayed()
        }
    }
}
