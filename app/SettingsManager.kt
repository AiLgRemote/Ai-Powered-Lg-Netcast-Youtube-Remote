package com.tatilacratita.lgcast.sampler

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

class SettingsManager(private val context: Context) {
    private val sharedPreferences = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_THEME = "key_theme"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        const val KEY_LAST_ACTIVITY = "key_last_activity"
        const val ACTIVITY_MAIN = "main"
        const val ACTIVITY_REMOTE = "remote"

        const val KEY_LANGUAGE = "key_language"
        const val LANG_ROMANIAN = "ro"
        const val LANG_ENGLISH = "en"
        const val LANG_SYSTEM = "system"
    }

    fun applyTheme() {
        val theme = sharedPreferences.getString(KEY_THEME, THEME_DARK)
        applyTheme(theme)
    }

    fun applyTheme(theme: String?) {
        when (theme) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun setTheme(theme: String) {
        sharedPreferences.edit().putString(KEY_THEME, theme).apply()
        applyTheme(theme)
    }

    fun getTheme(): String {
        return sharedPreferences.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
    }

    fun setLastActivity(activity: String) {
        sharedPreferences.edit().putString(KEY_LAST_ACTIVITY, activity).apply()
    }

    fun getLastActivity(): String {
        return sharedPreferences.getString(KEY_LAST_ACTIVITY, ACTIVITY_MAIN) ?: ACTIVITY_MAIN
    }

    fun applyLanguage(base: Context, langCode: String?): Context {
        val locale = when (langCode) {
            LANG_ROMANIAN -> Locale("ro")
            LANG_ENGLISH -> Locale("en")
            else -> { // LANG_SYSTEM or null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Resources.getSystem().configuration.locales.get(0)
                } else {
                    @Suppress("DEPRECATION")
                    Resources.getSystem().configuration.locale
                }
            }
        }

        // Update the app's default locale
        Locale.setDefault(locale)

        // Create a new configuration with the selected locale
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)

        // Apply the configuration to the context
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            base.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            base.resources.updateConfiguration(config, base.resources.displayMetrics)
            base
        }
    }

    fun setLanguage(langCode: String) {
        sharedPreferences.edit().putString(KEY_LANGUAGE, langCode).apply()
        // Language will be applied on next activity recreation/startup
    }

    fun getLanguage(): String {
        return sharedPreferences.getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
    }
}
