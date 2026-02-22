package com.zeni.voiceai.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app preferences using SharedPreferences.
 * Remembers server URL and voice selection between sessions.
 */
class PreferencesManager(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "zeni_preferences"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_VOICE_PREFERENCE = "voice_preference"
        private const val KEY_LANGUAGE_PREFERENCE = "language_preference"
        private const val KEY_PERSONALITY = "personality"
        
        // Defaults
        private const val DEFAULT_SERVER_URL = "ws://192.168.1.33:8765/voice"
        private const val DEFAULT_VOICE = "Kore"
        private const val DEFAULT_LANGUAGE = "en"
        private const val DEFAULT_PERSONALITY = "assistant"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Get saved server URL or default.
     */
    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }
    
    /**
     * Save server URL.
     */
    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }
    
    /**
     * Get saved voice preference or default.
     */
    fun getVoicePreference(): String {
        return prefs.getString(KEY_VOICE_PREFERENCE, DEFAULT_VOICE) ?: DEFAULT_VOICE
    }
    
    /**
     * Save voice preference.
     */
    fun setVoicePreference(voice: String) {
        prefs.edit().putString(KEY_VOICE_PREFERENCE, voice).apply()
    }
    
    /**
     * Get saved language preference or default.
     */
    fun getLanguagePreference(): String {
        return prefs.getString(KEY_LANGUAGE_PREFERENCE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }
    
    /**
     * Save language preference.
     */
    fun setLanguagePreference(language: String) {
        prefs.edit().putString(KEY_LANGUAGE_PREFERENCE, language).apply()
    }
    
    /**
     * Get personality mode (assistant or human).
     */
    fun getPersonality(): String {
        return prefs.getString(KEY_PERSONALITY, DEFAULT_PERSONALITY) ?: DEFAULT_PERSONALITY
    }
    
    /**
     * Save personality mode.
     */
    fun setPersonality(personality: String) {
        prefs.edit().putString(KEY_PERSONALITY, personality).apply()
    }
    
    /**
     * Get HTTP base URL from WebSocket URL.
     * Converts ws://host:port/path to http://host:port
     */
    fun getHttpBaseUrl(): String {
        val wsUrl = getServerUrl()
        return wsUrl
            .replace("ws://", "http://")
            .replace("wss://", "https://")
            .replace("/voice", "")
            .trimEnd('/')
    }
}
