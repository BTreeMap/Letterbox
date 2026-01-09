package org.joefang.letterbox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore instance for user preferences.
 * This is a singleton to ensure only one DataStore instance is active for the file.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

/**
 * Proxy mode for fetching remote images.
 */
enum class ProxyMode {
    /** Use Cloudflare WARP via WireGuard tunnel (recommended) */
    WARP,
    /** Load images directly without proxy (exposes IP address) */
    DIRECT
}

/**
 * Repository for managing user preferences using Jetpack DataStore.
 * 
 * This class provides a safe wrapper around the DataStore singleton.
 * Multiple instances of this repository can be created safely as they all
 * share the same underlying DataStore instance.
 * 
 * Preferences:
 * - ALWAYS_LOAD_REMOTE_IMAGES: Whether to automatically load remote images (default: false)
 * - ENABLE_PRIVACY_PROXY: Whether to use privacy proxy for remote images (default: true)
 * - PROXY_MODE: Which proxy to use (default: WARP)
 * - CLOUDFLARE_TERMS_ACCEPTED: Whether user has accepted Cloudflare terms (default: false)
 */
class UserPreferencesRepository(private val context: Context) {
    
    companion object {
        private val KEY_ALWAYS_LOAD_REMOTE_IMAGES = booleanPreferencesKey("always_load_remote_images")
        private val KEY_ENABLE_PRIVACY_PROXY = booleanPreferencesKey("enable_privacy_proxy")
        private val KEY_PROXY_MODE = stringPreferencesKey("proxy_mode")
        private val KEY_CLOUDFLARE_TERMS_ACCEPTED = booleanPreferencesKey("cloudflare_terms_accepted")
    }
    
    /**
     * Flow of whether to always load remote images automatically.
     */
    val alwaysLoadRemoteImages: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_ALWAYS_LOAD_REMOTE_IMAGES] ?: false
        }
    
    /**
     * Flow of whether to use privacy proxy for remote images.
     */
    val enablePrivacyProxy: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_ENABLE_PRIVACY_PROXY] ?: true
        }
    
    /**
     * Flow of the current proxy mode.
     */
    val proxyMode: Flow<ProxyMode> = context.dataStore.data
        .map { preferences ->
            when (preferences[KEY_PROXY_MODE]) {
                "DIRECT" -> ProxyMode.DIRECT
                else -> ProxyMode.WARP // Default to WARP
            }
        }
    
    /**
     * Flow of whether user has accepted Cloudflare terms of service.
     */
    val cloudflareTermsAccepted: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_CLOUDFLARE_TERMS_ACCEPTED] ?: false
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
     * Set whether to use privacy proxy for remote images.
     */
    suspend fun setEnablePrivacyProxy(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ENABLE_PRIVACY_PROXY] = value
        }
    }
    
    /**
     * Set the proxy mode for remote images.
     */
    suspend fun setProxyMode(mode: ProxyMode) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PROXY_MODE] = mode.name
        }
    }
    
    /**
     * Set whether user has accepted Cloudflare terms of service.
     */
    suspend fun setCloudflareTermsAccepted(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CLOUDFLARE_TERMS_ACCEPTED] = value
        }
    }
}
