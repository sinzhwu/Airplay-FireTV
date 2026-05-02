package com.airplay.firetv.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "airplay_settings")

class SettingsRepository(context: Context) {

    private val dataStore = context.dataStore

    companion object {
        private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        private val KEY_AUTO_START = booleanPreferencesKey("auto_start")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            displayName = prefs[KEY_DISPLAY_NAME] ?: "FireTV AirPlay",
            autoStart = prefs[KEY_AUTO_START] ?: true
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val current = AppSettings(
                displayName = prefs[KEY_DISPLAY_NAME] ?: "FireTV AirPlay",
                autoStart = prefs[KEY_AUTO_START] ?: true
            )
            val updated = transform(current)
            prefs[KEY_DISPLAY_NAME] = updated.displayName
            prefs[KEY_AUTO_START] = updated.autoStart
        }
    }
}
