package com.tableos.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class AppsSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.apps_preferences, rootKey)

        findPreference<Preference>("apps")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
            true
        }
    }
}