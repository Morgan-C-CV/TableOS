package com.tableos.beakerlab

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val TAG = "BeakerMain"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.i(TAG, "onCreate")
    }

    override fun onResume() {
        super.onResume()
        try {
            Log.i(TAG, "onResume: load keystone config")
            findViewById<KeystoneWarpLayout>(R.id.keystone_root)?.loadConfig()
        } catch (_: Exception) { /* ignore */ }
    }
}