package com.amaru.palantir

import android.content.Context
import android.content.SharedPreferences

class `KeyManager-old`(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("palantir_prefs", Context.MODE_PRIVATE)

    fun getApiKey(): String {
        return prefs.getString("openai_api_key", "") ?: ""
    }

    fun saveApiKey(key: String) {
        prefs.edit().putString("openai_api_key", key.trim()).apply()
    }

    fun hasApiKey(): Boolean {
        return getApiKey().isNotBlank()
    }
}