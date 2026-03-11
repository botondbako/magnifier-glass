package com.magnifierglass

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import androidx.preference.PreferenceManager
import java.util.Locale

object LocaleHelper {
    @JvmStatic
    fun getLocale(context: Context): Locale {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val lang = prefs.getString(context.getString(R.string.key_preference_language), "default") ?: "default"
        return if (lang == "default") Resources.getSystem().configuration.locales[0] else Locale(lang)
    }

    /**
     * Apply the user's preferred locale to the activity's resources.
     * Must be called before setContentView() to take effect on inflated layouts.
     *
     * Uses the deprecated updateConfiguration() because createConfigurationContext()
     * returns a new Context without modifying the activity's existing resources.
     */
    @JvmStatic
    fun applyLocale(activity: Activity) {
        val locale = getLocale(activity)
        Locale.setDefault(locale)
        val config = activity.resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
    }
}
