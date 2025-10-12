package com.tableos.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class DisplaySettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.display_preferences, rootKey)

        findPreference<Preference>("brightness")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            true
        }
        findPreference<Preference>("screensaver")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
            true
        }

        findPreference<Preference>("desktop_calibration")?.setOnPreferenceClickListener {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, DesktopCalibrationFragment())
                .addToBackStack(null)
                .commit()
            true
        }
    }
}