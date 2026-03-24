package com.example.nearbyshare.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

class SettingsService(private val context: Context) : ApplicationService() {

    companion object {
        private const val TAG = "SettingsService"
        const val PREF_NAME = "settings"
        const val KEY_CONNECTION_STRATEGY = "connection_strategy"
        const val STRATEGY_AUTO = "auto"
        const val STRATEGY_MANUAL = "manual"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_COLOR = "user_color"
        const val MAX_NAME_LENGTH = 18
        const val COLOR_YELLOW = 0
        const val COLOR_GREEN = 1
        const val COLOR_PURPLE = 2
        const val COLOR_ORANGE = 3
        const val COLOR_BLUE = 4
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isAutoConnectEnabled(): Boolean {
        val enabled = prefs.getString(KEY_CONNECTION_STRATEGY, STRATEGY_AUTO) == STRATEGY_AUTO
        Log.d(TAG, "isAutoConnectEnabled=$enabled")
        return enabled
    }

    fun setConnectionStrategy(strategy: String) {
        Log.d(TAG, "setConnectionStrategy strategy=$strategy")
        prefs.edit { putString(KEY_CONNECTION_STRATEGY, strategy) }
    }

    fun getConnectionStrategy(): String {
        return prefs.getString(KEY_CONNECTION_STRATEGY, STRATEGY_AUTO) ?: STRATEGY_AUTO
    }

    fun getUserName(): String {
        return prefs.getString(KEY_USER_NAME, "") ?: ""
    }

    fun setUserName(name: String) {
        val trimmed = if (name.length > MAX_NAME_LENGTH) name.take(MAX_NAME_LENGTH) else name
        Log.d(TAG, "setUserName name=$trimmed")
        prefs.edit { putString(KEY_USER_NAME, trimmed) }
    }

    fun getUserColor(): Int {
        return prefs.getInt(KEY_USER_COLOR, COLOR_YELLOW)
    }

    fun setUserColor(color: Int) {
        Log.d(TAG, "setUserColor color=$color")
        prefs.edit { putInt(KEY_USER_COLOR, color) }
    }
}
