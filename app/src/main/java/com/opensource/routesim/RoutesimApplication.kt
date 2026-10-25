package com.opensource.routesim

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class RoutesimApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        AppCompatDelegate.setDefaultNightMode(loadThemeMode(this))
    }

    companion object {
        const val PREFERENCES_NAME = "routesim_ui"
        private const val PREF_THEME_MODE = "theme_mode"
        private lateinit var instance: RoutesimApplication

        fun loadThemeMode(context: Context): Int {
            val saved = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            return saved.takeIf {
                it == AppCompatDelegate.MODE_NIGHT_NO ||
                    it == AppCompatDelegate.MODE_NIGHT_YES ||
                    it == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            } ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        fun setThemeMode(mode: Int) {
            check(::instance.isInitialized) { "Application is not initialized" }
            instance.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_THEME_MODE, mode)
                .apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
