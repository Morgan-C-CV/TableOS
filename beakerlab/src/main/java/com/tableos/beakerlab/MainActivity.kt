package com.tableos.beakerlab

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.view.KeyEvent
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.roundToInt
// 使用本模块内的 KeystoneWarpLayout（com.tableos.beakerlab.KeystoneWarpLayout）

class MainActivity : AppCompatActivity() {
    private val TAG = "BeakerMain"
    private lateinit var lightingArea: android.view.View
    private var lightingBrightness: Float = 1.0f // 0.0f ~ 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.i(TAG, "onCreate")

        // 进入 BeakerLab 即启用沉浸式，隐藏系统状态栏与导航栏
        enableImmersiveMode()

        lightingArea = findViewById(R.id.lighting_area)
        val canvas = findViewById<BeakerCanvasView>(R.id.canvas)
        val addBtn = findViewById<android.widget.Button>(R.id.add_card_btn)
        val testShapeDetectionBtn = findViewById<android.widget.Button>(R.id.test_shape_detection_btn)
        val detectionResultView = findViewById<ImageView>(R.id.detection_result_view)

        // 初始化形状检测器
        if (!ShapeDetectorJNI.init()) {
            Log.e(TAG, "Failed to initialize shape detector")
            Toast.makeText(this, "形状检测器初始化失败", Toast.LENGTH_SHORT).show()
        } else {
            Log.i(TAG, "Shape detector initialized successfully")
            Log.i(TAG, "Shape detector version: ${ShapeDetectorJNI.getVersion()}")
        }

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

        testShapeDetectionBtn?.setOnClickListener {
            performShapeDetection(detectionResultView)
        }
    }

    private fun performShapeDetection(resultView: ImageView) {
        try {
            // 从 assets 或 src 目录加载 t.png
            val inputStream = assets.open("t.png")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (bitmap != null) {
                Log.i(TAG, "Loaded test image: ${bitmap.width}x${bitmap.height}")
                
                // 执行形状检测并获取 JSON 结果
                val jsonResult = ShapeDetectorJNI.detectShapesFromBitmap(bitmap)
                Log.i(TAG, "Shape detection result: $jsonResult")
                
                // 获取带注释的图像
                val annotatedBitmap = ShapeDetectorJNI.annotateImage(bitmap)
                
                if (annotatedBitmap != null) {
                    // 在 UI 线程中显示结果
                    runOnUiThread {
                        resultView.setImageBitmap(annotatedBitmap)
                        resultView.visibility = View.VISIBLE
                        Toast.makeText(this, "检测完成，查看右上角结果", Toast.LENGTH_SHORT).show()
                    }
                    Log.i(TAG, "Annotated image displayed")
                } else {
                    Log.e(TAG, "Failed to get annotated image")
                    runOnUiThread {
                        Toast.makeText(this, "图像注释失败", Toast.LENGTH_SHORT).show()
                    }
                }
                
                bitmap.recycle()
            } else {
                Log.e(TAG, "Failed to load test image")
                Toast.makeText(this, "无法加载测试图片", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during shape detection", e)
            Toast.makeText(this, "形状检测出错: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理形状检测器
        ShapeDetectorJNI.cleanup()
        Log.i(TAG, "Shape detector cleaned up")
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
        // 保持沉浸式，防止系统栏在切换后重新出现
        enableImmersiveMode()
    }
}

private fun AppCompatActivity.enableImmersiveMode() {
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