package com.tableos.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.provider.Settings

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // 兜底：若系统不支持标准 Settings Action，则尝试直接启动包名
            try {
                val intent = Intent().setClassName("com.android.settings", "com.android.settings.Settings")
                startActivity(intent)
            } catch (_: Exception) {
                // 无法打开系统设置则直接结束
            }
        }
        finish()
    }
}