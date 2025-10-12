package com.tableos.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class RootSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        findPreference<Preference>("category_network")?.setOnPreferenceClickListener {
            navigate(NetworkSettingsFragment())
            true
        }
        findPreference<Preference>("category_display")?.setOnPreferenceClickListener {
            navigate(DisplaySettingsFragment())
            true
        }
        findPreference<Preference>("category_input")?.setOnPreferenceClickListener {
            navigate(InputControlSettingsFragment())
            true
        }
        findPreference<Preference>("category_sound")?.setOnPreferenceClickListener {
            navigate(SoundSettingsFragment())
            true
        }
        findPreference<Preference>("category_apps")?.setOnPreferenceClickListener {
            navigate(AppsSettingsFragment())
            true
        }
        findPreference<Preference>("category_storage")?.setOnPreferenceClickListener {
            navigate(StorageSettingsFragment())
            true
        }
        findPreference<Preference>("category_about")?.setOnPreferenceClickListener {
            navigate(AboutSettingsFragment())
            true
        }
    }

    private fun navigate(fragment: PreferenceFragmentCompat) {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}