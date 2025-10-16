package com.tableos.testapp

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 获取版本信息
        val versionName = getVersionName()
        val packageName = packageName

        // 设置版本号显示
        val tvVersion = findViewById<TextView>(R.id.tv_version)
        tvVersion.text = versionName

        // 设置包名显示
        val tvPackageName = findViewById<TextView>(R.id.tv_package_name)
        tvPackageName.text = packageName
    }

    private fun getVersionName(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "Unknown"
        }
    }
}