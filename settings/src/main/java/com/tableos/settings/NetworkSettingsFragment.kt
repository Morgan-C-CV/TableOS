package com.tableos.settings

import android.content.Intent
import android.widget.Toast
import android.content.ComponentName
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
            val pm = requireContext().packageManager

            // 1) 标准系统蓝牙设置
            val intents = mutableListOf<Intent>()
            intents += Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

            // 2) Android TV 的“Remotes & Accessories”（不同设备/版本有所差异）
            intents += Intent().apply {
                component = ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.accessories.AccessoriesActivity"
                )
            }
            intents += Intent().apply {
                component = ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.bluetooth.BluetoothActivity"
                )
            }
            intents += Intent().apply {
                component = ComponentName(
                    "com.google.android.tv.settings",
                    "com.google.android.tv.settings.accessories.AccessoriesActivity"
                )
            }
            intents += Intent().apply {
                component = ComponentName(
                    "com.google.android.tv.settings",
                    "com.google.android.tv.settings.bluetooth.BluetoothActivity"
                )
            }

            // 3) AOSP 显式组件（不同版本可能命名不同）
            intents += Intent().apply {
                component = ComponentName(
                    "com.android.settings",
                    "com.android.settings.bluetooth.BluetoothSettings"
                )
            }
            intents += Intent().apply {
                component = ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$BluetoothSettingsActivity"
                )
            }

            // 4) 兜底到总设置页
            intents += Intent(Settings.ACTION_SETTINGS)

            var started = false
            for (i in intents) {
                try {
                    if (i.resolveActivity(pm) != null) {
                        startActivity(i)
                        started = true
                        break
                    }
                } catch (_: Exception) { /* 继续尝试下一种 */ }
            }

            if (!started) {
                Toast.makeText(requireContext(), "设备不支持蓝牙设置页面", Toast.LENGTH_SHORT).show()
            }
            true
        }
        findPreference<Preference>("internet")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            true
        }
    }
}