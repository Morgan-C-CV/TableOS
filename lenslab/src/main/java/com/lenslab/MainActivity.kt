package com.lenslab

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.lenslab.ui.OpticsView
import com.tableos.warp.KeystoneWarpLayout

class MainActivity : AppCompatActivity() {
    private lateinit var opticsView: OpticsView
    private var canvasBrightness: Float = 1.0f // 0.0f ~ 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        opticsView = findViewById(R.id.optics_view)
        val btnEmitter = findViewById<Button>(R.id.btn_add_emitter)
        val btnMirror = findViewById<Button>(R.id.btn_add_mirror)
        val btnPrism = findViewById<Button>(R.id.btn_add_prism)
        val btnConvex = findViewById<Button>(R.id.btn_add_convex_lens)
        val btnConcave = findViewById<Button>(R.id.btn_add_concave_lens)
        val btnFitLens = findViewById<Button>(R.id.btn_fit_lens)
        val btnFitMirror = findViewById<Button>(R.id.btn_fit_mirror)
        val btnShowHelpers = findViewById<android.widget.CheckBox>(R.id.btn_show_helpers)

        applyCanvasBrightness()
        opticsView.requestFocus()

        btnEmitter.setOnClickListener {
            opticsView.setMode(OpticsView.Mode.ADD_EMITTER)
        }
        btnMirror.setOnClickListener {
            opticsView.setMode(OpticsView.Mode.ADD_PLANE_MIRROR)
        }
        btnPrism.setOnClickListener {
            opticsView.setMode(OpticsView.Mode.ADD_TRIANGULAR_PRISM)
        }
        btnConvex.setOnClickListener {
            opticsView.setMode(OpticsView.Mode.ADD_CONVEX_LENS)
        }
        btnConcave.setOnClickListener {
            opticsView.setMode(OpticsView.Mode.ADD_CONCAVE_LENS)
        }
        btnFitLens.setOnClickListener {
            opticsView.post {
                try {
                    val am = assets
                    val jsons = am.list("")?.filter { it.endsWith(".json", true) } ?: emptyList()
                    val pngs = am.list("")?.filter { it.endsWith(".png", true) } ?: emptyList()
                    // Prefer 1.json / 1.png first, then 2.*, else fallback to first available
                    val targetJson = when {
                        jsons.any { it.equals("1.json", ignoreCase = true) } -> "1.json"
                        jsons.any { it.equals("2.json", ignoreCase = true) } -> "2.json"
                        else -> jsons.firstOrNull()
                    }
                    val targetPng = when {
                        pngs.any { it.equals("1.png", ignoreCase = true) } -> "1.png"
                        pngs.any { it.equals("2.png", ignoreCase = true) } -> "2.png"
                        else -> pngs.firstOrNull()
                    }
                    if (targetJson != null) {
                        opticsView.importFittedLensParamsFromAsset(targetJson)
                    }
                    if (targetPng != null) {
                        // 仅进行拟合并添加物理透镜，不绘制蓝色轮廓叠加
                        opticsView.importFittedLensOverlayFromAsset(targetPng, scaleToView = true, drawOverlay = false)
                    }
                } catch (_: Exception) {
                }
            }
        }
        btnFitMirror.setOnClickListener {
            opticsView.post {
                try {
                    val am = assets
                    val pngs = am.list("")?.filter { it.endsWith(".png", true) } ?: emptyList()
                    val targetPng = when {
                        pngs.any { it.equals("1.png", ignoreCase = true) } -> "1.png"
                        pngs.any { it.equals("2.png", ignoreCase = true) } -> "2.png"
                        else -> pngs.firstOrNull()
                    }
                    if (targetPng != null) {
                        opticsView.importPlaneMirrorFromAsset(targetPng)
                    }
                } catch (_: Exception) {
                }
            }
        }
        btnShowHelpers.setOnCheckedChangeListener { _, isChecked ->
            opticsView.setShowHelpers(isChecked)
        }
    }

    override fun onResume() {
        super.onResume()
        // 加载并应用桌面透视配置
        findViewById<KeystoneWarpLayout>(R.id.keystone_root)?.loadConfig()
    }

    private fun applyCanvasBrightness() {
        val v = (canvasBrightness.coerceIn(0f, 1f) * 255f).toInt()
        opticsView.setBackgroundColor(Color.rgb(v, v, v))
    }

    private fun adjustBrightnessBy(delta: Float) {
        canvasBrightness = (canvasBrightness + delta).coerceIn(0f, 1f)
        applyCanvasBrightness()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val inCanvas = ::opticsView.isInitialized && opticsView.hasFocus()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (inCanvas) { adjustBrightnessBy(+0.1f); return true }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (inCanvas) { adjustBrightnessBy(-0.1f); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}