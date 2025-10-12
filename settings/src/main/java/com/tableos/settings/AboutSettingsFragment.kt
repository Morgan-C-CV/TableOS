package com.tableos.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.tableos.settings.BuildConfig

class AboutSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.about_preferences, rootKey)

        findPreference<Preference>("about_device")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            true
        }

        // 动态更新系统版本显示为 TableOS v{versionName}
        findPreference<Preference>("system_version")?.let { pref ->
            val version = BuildConfig.VERSION_NAME
            pref.summary = "目前系统为TableOS v$version"
        }
    }
}