package com.tableos.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class NetworkSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.network_preferences, rootKey)

        findPreference<Preference>("wifi")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            true
        }
        findPreference<Preference>("bluetooth")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            true
        }
        findPreference<Preference>("internet")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            true
        }
    }
}