package org.joefang.letterbox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for managing user preferences using Jetpack DataStore.
 * 
 * Preferences:
 * - ALWAYS_LOAD_REMOTE_IMAGES: Whether to automatically load remote images (default: false)
 * - ENABLE_PRIVACY_PROXY: Whether to use DuckDuckGo proxy for remote images (default: true)
 */
class UserPreferencesRepository(private val context: Context) {
    
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")
    
    companion object {
        private val KEY_ALWAYS_LOAD_REMOTE_IMAGES = booleanPreferencesKey("always_load_remote_images")
        private val KEY_ENABLE_PRIVACY_PROXY = booleanPreferencesKey("enable_privacy_proxy")
    }
    
    /**
     * Flow of whether to always load remote images automatically.
     */
    val alwaysLoadRemoteImages: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_ALWAYS_LOAD_REMOTE_IMAGES] ?: false
        }
    
    /**
     * Flow of whether to use DuckDuckGo privacy proxy for remote images.
     */
    val enablePrivacyProxy: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_ENABLE_PRIVACY_PROXY] ?: true
        }
    
    /**
     * Set whether to always load remote images automatically.
     */
    suspend fun setAlwaysLoadRemoteImages(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ALWAYS_LOAD_REMOTE_IMAGES] = value
        }
    }
    
    /**
     * Set whether to use DuckDuckGo privacy proxy for remote images.
     */
    suspend fun setEnablePrivacyProxy(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ENABLE_PRIVACY_PROXY] = value
        }
    }
}
