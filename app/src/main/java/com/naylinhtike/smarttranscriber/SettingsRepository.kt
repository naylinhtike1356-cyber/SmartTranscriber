package com.naylinhtike.smarttranscriber

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "smart_transcriber_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        private val KEY_GEMINI_KEYS = stringPreferencesKey("gemini_api_keys")
        private val KEY_NOTION_API_KEY = stringPreferencesKey("notion_api_key")
        private val KEY_NOTION_DATABASE_ID = stringPreferencesKey("notion_database_id")
        private val KEY_DEFAULT_LANGUAGE = stringPreferencesKey("default_language")
    }

    val geminiKeysFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_GEMINI_KEYS].orEmpty()
        raw.split(",", "\n", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    val geminiKeysRawFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GEMINI_KEYS].orEmpty()
    }

    val notionApiKeyFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTION_API_KEY].orEmpty()
    }

    val notionDatabaseIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTION_DATABASE_ID].orEmpty()
    }

    val defaultLanguageFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_LANGUAGE] ?: "my-MM"
    }

    suspend fun saveGeminiKeys(keysRaw: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GEMINI_KEYS] = keysRaw.trim()
        }
    }

    suspend fun saveNotionApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTION_API_KEY] = key.trim()
        }
    }

    suspend fun saveNotionDatabaseId(databaseId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTION_DATABASE_ID] = databaseId.trim()
        }
    }

    suspend fun saveDefaultLanguage(language: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_LANGUAGE] = language
        }
    }
}
