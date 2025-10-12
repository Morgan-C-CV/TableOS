package com.tableos.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.provider.Settings
import android.app.AlertDialog
import android.net.Uri

class SettingsActivity : AppCompatActivity() {
    private var writeSettingsPromptShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, RootSettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        // 启动时申请“修改系统设置”权限，便于保存桌面矫正
        if (!Settings.System.canWrite(this) && !writeSettingsPromptShown) {
            writeSettingsPromptShown = true
            try {
                AlertDialog.Builder(this)
                    .setTitle("需要授权")
                    .setMessage("请允许修改系统设置以保存矫正")
                    .setPositiveButton("前往授权") { _, _ ->
                        try {
                            val intent = android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                data = Uri.parse("package:" + packageName)
                            }
                            startActivity(intent)
                        } catch (_: Exception) { /* ignore */ }
                    }
                    .setNegativeButton("稍后") { _, _ -> }
                    .setCancelable(true)
                    .show()
            } catch (_: Exception) { /* ignore */ }
        }
    }
}