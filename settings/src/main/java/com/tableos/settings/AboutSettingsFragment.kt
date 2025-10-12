package com.tableos.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class AboutSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.about_preferences, rootKey)

        findPreference<Preference>("about_device")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            true
        }
    }
}