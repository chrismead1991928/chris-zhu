package com.familyrecipebox.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_LANGUAGE = "selected_language"

    private val SUPPORTED_LANGUAGES = listOf("en", "zh", "ja", "de", "fr")

    fun getSupportedLanguages(): List<String> = SUPPORTED_LANGUAGES

    fun getLanguageDisplayName(code: String): String {
        return when (code) {
            "en" -> "English"
            "zh" -> "简体中文"
            "ja" -> "日本語"
            "de" -> "Deutsch"
            "fr" -> "Français"
            else -> code
        }
    }

    fun getCurrentLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, null)
            ?: getSystemLanguage(context)
    }

    private fun getSystemLanguage(context: Context): String {
        val systemLocale = context.resources.configuration.locales.get(0)
        val code = systemLocale.language
        return if (code in SUPPORTED_LANGUAGES) code else "en"
    }

    fun setLanguage(context: Context, languageCode: String) {
        if (languageCode !in SUPPORTED_LANGUAGES) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, languageCode)
            .apply()
        try {
            val localeList = LocaleListCompat.create(Locale(languageCode))
            AppCompatDelegate.setApplicationLocales(localeList)
        } catch (e: Exception) {
            // Some OEM skins may crash when calling setApplicationLocales; keep the pref saved
        }
    }

    fun applyPersistedLocale(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val languageCode = prefs.getString(KEY_LANGUAGE, null) ?: return
        if (languageCode in SUPPORTED_LANGUAGES) {
            val localeList = LocaleListCompat.create(Locale(languageCode))
            AppCompatDelegate.setApplicationLocales(localeList)
        }
    }
}
