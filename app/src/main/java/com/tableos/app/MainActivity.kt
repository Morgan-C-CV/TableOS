package com.tableos.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.ViewParent
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppsAdapter
    private lateinit var lightingArea: View
    private var lightingBrightness: Float = 1.0f // 0.0f ~ 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lightingArea = findViewById(R.id.lighting_area)
        recyclerView = findViewById(R.id.apps_recycler)
        adapter = AppsAdapter(emptyList()) { app ->
            launchApp(app)
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = GridLayoutManager(this, 1)

        applyLightingBrightness()
        lightingArea.requestFocus()
        loadApps()
    }

    private fun applyLightingBrightness() {
        val v = (lightingBrightness.coerceIn(0f, 1f) * 255f).roundToInt()
        lightingArea.setBackgroundColor(Color.rgb(v, v, v))
    }

    private fun adjustBrightnessBy(delta: Float) {
        lightingBrightness = (lightingBrightness + delta).coerceIn(0f, 1f)
        applyLightingBrightness()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val inLighting = lightingArea.hasFocus()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (inLighting) { adjustBrightnessBy(+0.1f); return true }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (inLighting) { adjustBrightnessBy(-0.1f); return true }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (inLighting) { moveFocusToAppBar(); return true }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (isFocusInAppBar()) { lightingArea.requestFocus(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isFocusInAppBar(): Boolean {
        val cf = currentFocus ?: return false
        if (cf === recyclerView) return true
        var parent: ViewParent? = cf.parent
        while (parent != null) {
            if (parent === recyclerView) return true
            parent = parent.parent
        }
        return false
    }

    private fun moveFocusToAppBar() {
        recyclerView.requestFocus()
        recyclerView.post {
            val v = recyclerView.layoutManager?.findViewByPosition(0)
            v?.requestFocus()
        }
    }

    private fun loadApps() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(mainIntent, 0)

        val apps = activities.map { info ->
            val label = info.loadLabel(pm).toString()
            val icon = info.loadIcon(pm)
            val pkg = info.activityInfo.packageName
            val act = info.activityInfo.name
            AppInfo(label, pkg, act, icon)
        }.sortedBy { it.label.lowercase() }.toMutableList()

        // 插入系统设置入口到 App Bar 顶部（若未存在）
        try {
            val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
            val resolved = settingsIntent.resolveActivity(pm)
            if (resolved != null) {
                val settingsPkg = resolved.packageName
                val settingsAct = resolved.className
                val alreadyHas = apps.any { it.packageName == settingsPkg }
                if (!alreadyHas) {
                    val appInfo = pm.getApplicationInfo(settingsPkg, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(settingsPkg)
                    val settingsEntry = AppInfo(label, settingsPkg, settingsAct, icon)
                    apps.add(0, settingsEntry)
                }
            }
        } catch (_: Exception) {
            // 忽略：设备可能无标准系统设置入口
        }

        adapter.submitList(apps)
    }

    private fun launchApp(app: AppInfo) {
        try {
            val component = ComponentName(app.packageName, app.activityName)
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.component = component
            startActivity(intent)
        } catch (e: Exception) {
            // 失败时忽略启动
        }
    }
}