package com.magnifierglass;

import android.content.SharedPreferences;

import com.magnifierglass.R;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;

import static org.junit.Assert.*;

/**
 * Tests for LocaleHelper.applyLocale() using Robolectric.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocaleHelperTest {

    @Test
    public void defaultLocale_usesSystemLocale() {
        try (ActivityScenario<MagnifierGlassActivity> scenario =
                     ActivityScenario.launch(MagnifierGlassActivity.class)) {
            scenario.onActivity(activity -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
                String key = activity.getString(R.string.key_preference_language);
                prefs.edit().putString(key, "default").apply();

                Locale systemLocale = android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0);
                LocaleHelper.applyLocale(activity);

                Locale applied = activity.getResources().getConfiguration().getLocales().get(0);
                assertEquals(systemLocale.getLanguage(), applied.getLanguage());
            });
        }
    }

    @Test
    public void germanLocale_appliesCorrectly() {
        try (ActivityScenario<MagnifierGlassActivity> scenario =
                     ActivityScenario.launch(MagnifierGlassActivity.class)) {
            scenario.onActivity(activity -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
                String key = activity.getString(R.string.key_preference_language);
                prefs.edit().putString(key, "de").apply();

                LocaleHelper.applyLocale(activity);

                assertEquals("de", activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
            });
        }
    }

    @Test
    public void switchLocale_changesEffectively() {
        try (ActivityScenario<MagnifierGlassActivity> scenario =
                     ActivityScenario.launch(MagnifierGlassActivity.class)) {
            scenario.onActivity(activity -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
                String key = activity.getString(R.string.key_preference_language);

                prefs.edit().putString(key, "fr").apply();
                LocaleHelper.applyLocale(activity);
                assertEquals("fr", activity.getResources().getConfiguration().getLocales().get(0).getLanguage());

                prefs.edit().putString(key, "es").apply();
                LocaleHelper.applyLocale(activity);
                assertEquals("es", activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
            });
        }
    }

    @Test
    public void missingPref_fallsToDefault() {
        try (ActivityScenario<MagnifierGlassActivity> scenario =
                     ActivityScenario.launch(MagnifierGlassActivity.class)) {
            scenario.onActivity(activity -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
                String key = activity.getString(R.string.key_preference_language);
                prefs.edit().remove(key).apply();

                LocaleHelper.applyLocale(activity);

                Locale systemLocale = android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0);
                Locale applied = activity.getResources().getConfiguration().getLocales().get(0);
                assertEquals(systemLocale.getLanguage(), applied.getLanguage());
            });
        }
    }
}
