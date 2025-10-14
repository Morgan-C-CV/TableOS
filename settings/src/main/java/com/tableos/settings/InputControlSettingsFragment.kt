package com.tableos.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

class InputControlSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.input_control_preferences, rootKey)
        // “视频输入设置”暂留空，不做点击处理

        findPreference<androidx.preference.Preference>("video_input_test")?.setOnPreferenceClickListener {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, VideoInputTestFragment())
                .addToBackStack(null)
                .commit()
            true
        }

        findPreference<androidx.preference.Preference>("video_input_recog_test")?.setOnPreferenceClickListener {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, InputRecognitionTestFragment())
                .addToBackStack(null)
                .commit()
            true
        }
    }
}