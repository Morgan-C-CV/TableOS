package com.tableos.beakerlab

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.view.KeyEvent
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private val TAG = "BeakerMain"
    private lateinit var lightingArea: android.view.View
    private var lightingBrightness: Float = 1.0f // 0.0f ~ 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.i(TAG, "onCreate")

        lightingArea = findViewById(R.id.lighting_area)
        val canvas = findViewById<BeakerCanvasView>(R.id.canvas)
        val addBtn = findViewById<android.widget.Button>(R.id.add_card_btn)

        applyLightingBrightness()
        lightingArea.requestFocus()

        addBtn?.setOnClickListener {
            val items = arrayOf("Na", "H₂O", "HCl", "NaOH", "Cl₂", "O₂", "H₂", "CO₂")
            AlertDialog.Builder(this)
                .setTitle("选择卡牌")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> canvas?.addCard(ChemicalType.Na)
                        1 -> canvas?.addCard(ChemicalType.H2O)
                        2 -> canvas?.addCard(ChemicalType.HCl)
                        3 -> canvas?.addCard(ChemicalType.NaOH)
                        4 -> canvas?.addCard(ChemicalType.Cl2)
                        5 -> canvas?.addCard(ChemicalType.O2)
                        6 -> canvas?.addCard(ChemicalType.H2)
                        7 -> canvas?.addCard(ChemicalType.CO2)
                    }
                }
                .show()
        }
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
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        try {
            Log.i(TAG, "onResume: load keystone config")
            findViewById<KeystoneWarpLayout>(R.id.keystone_root)?.loadConfig()
        } catch (_: Exception) { /* ignore */ }
    }
}