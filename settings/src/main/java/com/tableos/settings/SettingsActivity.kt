package com.tableos.settings

import android.os.Bundle
import android.util.Log
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

class SettingsActivity : AppCompatActivity() {
    private val TAG = "SettingsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Log.i(TAG, "onCreate: contentView set, root=${findViewById<android.view.View>(R.id.keystone_root)?.javaClass}")

        // 进入设置页即启用沉浸式，隐藏系统状态栏与导航栏
        enableImmersiveMode()

        // 注册 Fragment 生命周期日志，定位页面切换与校正页行为
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentAttached(fm: FragmentManager, f: Fragment, context: android.content.Context) {
                Log.i(TAG, "fragmentAttached: ${f::class.java.simpleName}")
            }
            override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
                Log.i(TAG, "fragmentCreated: ${f::class.java.simpleName}")
            }
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                Log.i(TAG, "fragmentResumed: ${f::class.java.simpleName}")
            }
            override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
                Log.i(TAG, "fragmentPaused: ${f::class.java.simpleName}")
            }
            override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                Log.i(TAG, "fragmentDestroyed: ${f::class.java.simpleName}")
            }
        }, true)

        if (savedInstanceState == null) {
            val start = intent?.getStringExtra("start_fragment")
            val fragment: Fragment = when (start) {
                "input_control" -> InputControlSettingsFragment()
                "input_recog_test" -> InputRecognitionTestFragment()
                "video_input_test" -> VideoInputTestFragment()
                else -> RootSettingsFragment()
            }
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, fragment)
                .commit()
            Log.i(TAG, "onCreate: initial fragment attached: ${fragment::class.java.simpleName}")
        }
    }

    override fun onResume() {
        super.onResume()
        // 启动时加载全局矫正配置并应用到设置界面
        try {
            Log.i(TAG, "onResume: loading keystone config for settings root")
            findViewById<KeystoneWarpLayout>(R.id.keystone_root)?.loadConfig()
            Log.i(TAG, "onResume: loadConfig invoked")
        } catch (_: Exception) { /* ignore */ }

        // 保持沉浸式，防止系统栏在切换后重新出现
        enableImmersiveMode()
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart")
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause")
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
    }

    override fun onBackPressed() {
        Log.i(TAG, "onBackPressed: delegating to super")
        super.onBackPressed()
    }

    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        }
    }
}