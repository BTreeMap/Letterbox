package org.joefang.letterbox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
    /** Use Cloudflare WARP via the MASQUE tunnel (recommended) */
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
 *
 * There is deliberately no "terms accepted" preference. Loading remote images is
 * itself the opt-in — it is off by default and requires a per-message tap or an
 * explicit setting — and the tunnel is disclosed in Settings. A separate consent
 * flag added a second condition that the "Show images" affordance did not check,
 * so tapping it silently loaded nothing; it also stranded every user who had
 * onboarded before the flag existed.
 */
class UserPreferencesRepository(private val context: Context) {
    
    companion object {
        private val KEY_ALWAYS_LOAD_REMOTE_IMAGES = booleanPreferencesKey("always_load_remote_images")
        private val KEY_ENABLE_PRIVACY_PROXY = booleanPreferencesKey("enable_privacy_proxy")
        private val KEY_PROXY_MODE = stringPreferencesKey("proxy_mode")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_epoch_millis")
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
     * Flow of whether the first-launch onboarding has been completed.
     *
     * Onboarding is purely informational: it explains that remote images and
     * update checks travel through a WARP tunnel. It gates nothing.
     */
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_ONBOARDING_COMPLETED] ?: false
        }

    /**
     * Flow of the epoch-millis timestamp of the last successful update check,
     * or 0 if an update check has never run.
     */
    val lastUpdateCheckEpochMillis: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_LAST_UPDATE_CHECK] ?: 0L
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
     * Complete first-launch onboarding.
     *
     * Records only that the introduction has been seen. Nothing downstream reads
     * it, so a user who onboarded under any previous build is in the same state
     * as one who onboards today.
     */
    suspend fun completeOnboarding() {
        context.dataStore.edit { preferences ->
            preferences[KEY_ONBOARDING_COMPLETED] = true
        }
    }

    /**
     * Record the timestamp of a completed update check.
     */
    suspend fun setLastUpdateCheck(epochMillis: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_UPDATE_CHECK] = epochMillis
        }
    }
}
