package com.tableos.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.apps_recycler)
        adapter = AppsAdapter(emptyList()) { app ->
            launchApp(app)
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = GridLayoutManager(this, 5)

        loadApps()
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
        }.sortedBy { it.label.lowercase() }

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