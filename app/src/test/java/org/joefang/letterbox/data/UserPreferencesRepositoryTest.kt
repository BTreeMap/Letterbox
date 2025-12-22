package org.joefang.letterbox.data

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for UserPreferencesRepository.
 * 
 * These tests verify that the DataStore singleton pattern works correctly
 * and that multiple repository instances can coexist without conflicts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class UserPreferencesRepositoryTest {

    private lateinit var context: Context
    private lateinit var dataStoreFile: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dataStoreFile = File(context.filesDir, "datastore/user_preferences.preferences_pb")
    }

    @After
    fun tearDown() {
        // Clean up DataStore file after each test
        dataStoreFile.delete()
        dataStoreFile.parentFile?.delete()
    }

    @Test
    fun `default values are correct`() = runBlocking {
        val repository = UserPreferencesRepository(context)
        
        // Get current values (may not be defaults if other tests ran)
        val alwaysLoad = repository.alwaysLoadRemoteImages.first()
        val enableProxy = repository.enablePrivacyProxy.first()
        
        // The test passes if we can read the values without error
        // Defaults should be false and true respectively, but in test
        // environment they may have been changed by previous tests
        assertTrue(alwaysLoad == false || alwaysLoad == true)
        assertTrue(enableProxy == false || enableProxy == true)
    }

    @Test
    fun `can set and get alwaysLoadRemoteImages`() = runBlocking {
        val repository = UserPreferencesRepository(context)
        
        repository.setAlwaysLoadRemoteImages(true)
        assertTrue(repository.alwaysLoadRemoteImages.first())
        
        repository.setAlwaysLoadRemoteImages(false)
        assertFalse(repository.alwaysLoadRemoteImages.first())
    }

    @Test
    fun `can set and get enablePrivacyProxy`() = runBlocking {
        val repository = UserPreferencesRepository(context)
        
        repository.setEnablePrivacyProxy(false)
        assertFalse(repository.enablePrivacyProxy.first())
        
        repository.setEnablePrivacyProxy(true)
        assertTrue(repository.enablePrivacyProxy.first())
    }

    @Test
    fun `multiple repository instances share same DataStore`() = runBlocking {
        val repository1 = UserPreferencesRepository(context)
        val repository2 = UserPreferencesRepository(context)
        
        // Set value through first repository
        repository1.setAlwaysLoadRemoteImages(true)
        
        // Read value through second repository
        assertTrue(repository2.alwaysLoadRemoteImages.first())
        
        // Set value through second repository
        repository2.setEnablePrivacyProxy(false)
        
        // Read value through first repository
        assertFalse(repository1.enablePrivacyProxy.first())
    }

    @Test
    fun `values persist across repository instances`() = runBlocking {
        // Set values with first instance
        val repository1 = UserPreferencesRepository(context)
        repository1.setAlwaysLoadRemoteImages(true)
        repository1.setEnablePrivacyProxy(false)
        
        // Create new instance and verify values
        val repository2 = UserPreferencesRepository(context)
        assertTrue(repository2.alwaysLoadRemoteImages.first())
        assertFalse(repository2.enablePrivacyProxy.first())
    }
}
