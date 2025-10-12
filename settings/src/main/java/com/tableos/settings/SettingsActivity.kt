package com.tableos.settings

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private val TAG = "SettingsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Log.d(TAG, "onCreate: contentView set, root=${findViewById<android.view.View>(R.id.keystone_root)?.javaClass}")

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, RootSettingsFragment())
                .commit()
            Log.d(TAG, "onCreate: RootSettingsFragment attached")
        }
    }

    override fun onResume() {
        super.onResume()
        // 启动时加载全局矫正配置并应用到设置界面
        try {
            Log.d(TAG, "onResume: loading keystone config for settings root")
            findViewById<KeystoneWarpLayout>(R.id.keystone_root)?.loadConfig()
            Log.d(TAG, "onResume: loadConfig invoked")
        } catch (_: Exception) { /* ignore */ }
    }
}