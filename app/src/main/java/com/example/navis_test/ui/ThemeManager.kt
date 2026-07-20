package com.example.navis_test.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {
    private const val PREFS = "theme_prefs"
    private const val KEY_NIGHT_MODE = "night_mode"

    private fun savedMode(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    fun applySaved(context: Context) {
        AppCompatDelegate.setDefaultNightMode(savedMode(context))
    }

    fun toggle(context: Context) {

        val isDark = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val newMode = if (isDark) {

            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NIGHT_MODE, newMode).apply()
        AppCompatDelegate.setDefaultNightMode(newMode)
    }
}
