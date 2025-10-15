package com.tableos.beakerlab

import android.Manifest
import android.content.pm.PackageManager
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
import android.graphics.YuvImage
import android.graphics.ImageFormat
import android.graphics.Rect
import android.widget.ImageView
import android.widget.Toast
import android.widget.TextView
import android.view.TextureView
import android.media.Image
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import java.nio.ByteBuffer
// 使用本模块内的 KeystoneWarpLayout（com.tableos.beakerlab.KeystoneWarpLayout）

class MainActivity : AppCompatActivity(), CameraManager.FrameCallback, ChemicalReactionEngine.ReactionCallback {
    private val TAG = "BeakerMain"
    private lateinit var lightingArea: android.view.View
    private var lightingBrightness: Float = 1.0f // 0.0f ~ 1.0f
    
    // Camera and reaction engine
    private lateinit var cameraManager: CameraManager
    private lateinit var reactionEngine: ChemicalReactionEngine
    private lateinit var cameraTextureView: TextureView
    private lateinit var reactionStatusText: TextView
    private val reactionHistory = mutableListOf<String>()
    
    // Permission request code
    private val CAMERA_PERMISSION_REQUEST_CODE = 1001

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
        cameraTextureView = findViewById<TextureView>(R.id.camera_texture_view)
        reactionStatusText = findViewById<TextView>(R.id.reaction_status_text)
        
        // Initialize camera manager and reaction engine
        cameraManager = CameraManager(this)
        cameraManager.setTextureView(cameraTextureView)
        cameraManager.setFrameCallback(this)
        
        reactionEngine = ChemicalReactionEngine()
        reactionEngine.setReactionCallback(this)
        
        // Check camera permission and start camera
        if (checkCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }

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
                
                // 设置图像尺寸用于反应引擎
                reactionEngine.setImageSize(bitmap.width, bitmap.height)
                
                // 执行形状检测并获取 JSON 结果
                val jsonResult = ShapeDetectorJNI.detectShapesFromBitmap(bitmap)
                Log.i(TAG, "Shape detection result: $jsonResult")
                
                // 测试化学反应检测
                if (jsonResult.isNotEmpty()) {
                    val elements = reactionEngine.parseDetectedElements(jsonResult)
                    Log.i(TAG, "Parsed elements: $elements")
                    
                    // 将检测到的形状传递给BeakerCanvasView
                    val beakerCanvasView = findViewById<BeakerCanvasView>(R.id.canvas)
                    beakerCanvasView.updateDetectedShapes(elements)
                    
                    reactionEngine.detectReactions(elements)
                }
                
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
        // 清理相机
        if (::cameraManager.isInitialized) {
            cameraManager.stopCamera()
        }
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
    
    // Camera permission methods
    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "需要相机权限才能进行化学反应检测", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun startCamera() {
        if (::cameraManager.isInitialized) {
            cameraManager.startCamera()
        }
    }
    
    // CameraManager.FrameCallback implementation
    override fun onFrameAvailable(image: Image) {
        // Convert Image to Bitmap and process with shape detection
        try {
            val bitmap = imageToBitmap(image)
            if (bitmap != null) {
                processFrameForReactions(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing camera frame", e)
        }
    }
    
    private fun imageToBitmap(image: Image): Bitmap? {
        try {
            // Convert YUV_420_888 Image to Bitmap
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // Copy Y plane
            yBuffer.get(nv21, 0, ySize)

            // Copy UV planes (interleaved for NV21 format)
            val uvPixelStride = planes[1].pixelStride
            if (uvPixelStride == 1) {
                // UV planes are already packed
                uBuffer.get(nv21, ySize, uSize)
                vBuffer.get(nv21, ySize + uSize, vSize)
            } else {
                // UV planes need to be interleaved
                val uvBuffer = ByteArray(uSize)
                val vvBuffer = ByteArray(vSize)
                uBuffer.get(uvBuffer)
                vBuffer.get(vvBuffer)
                
                var uvIndex = ySize
                for (i in 0 until uSize step uvPixelStride) {
                    nv21[uvIndex++] = vvBuffer[i]
                    nv21[uvIndex++] = uvBuffer[i]
                }
            }

            // Convert NV21 to RGB
            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, 
                image.width, image.height, null)
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 100, out)
            val imageBytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image to bitmap", e)
            return null
        }
    }
    
    private var imageSizeSet = false
    
    private fun processFrameForReactions(bitmap: Bitmap) {
        // Set image size for reaction engine on first frame
        if (!imageSizeSet) {
            reactionEngine.setImageSize(bitmap.width, bitmap.height)
            imageSizeSet = true
            Log.d(TAG, "Set image size for reaction engine: ${bitmap.width}x${bitmap.height}")
            
            // 同时设置BeakerCanvasView的相机尺寸
            runOnUiThread {
                val beakerCanvasView = findViewById<BeakerCanvasView>(R.id.canvas)
                beakerCanvasView.setCameraSize(bitmap.width, bitmap.height)
            }
        }
        
        // Use shape detection to find colored squares
        val detectionResult = ShapeDetectorJNI.detectShapesFromBitmap(bitmap)
        if (detectionResult.isNotEmpty()) {
            // Parse detection results and check for reactions
            val elements = reactionEngine.parseDetectedElements(detectionResult)
            
            // 将检测到的形状传递给BeakerCanvasView进行显示
            runOnUiThread {
                val beakerCanvasView = findViewById<BeakerCanvasView>(R.id.canvas)
                beakerCanvasView.updateDetectedShapes(elements)
            }
            
            reactionEngine.detectReactions(elements)
        }
    }
    
    // ChemicalReactionEngine.ReactionCallback implementation
    override fun onReactionDetected(reaction: ChemicalReaction, reactantElements: List<DetectedElement>) {
        runOnUiThread {
            showReactionFeedback(reaction, reactantElements)
        }
    }
    
    private fun showReactionFeedback(reaction: ChemicalReaction, reactantElements: List<DetectedElement>) {
        // 构建反应显示文本
        val reactantNames = reaction.reactants.map { getChemicalDisplayName(it) }
        val productNames = reaction.products.map { getChemicalDisplayName(it) }
        val reactionText = "${reactantNames.joinToString(" + ")} → ${productNames.joinToString(" + ")}"
        
        // 添加到历史记录
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val historyEntry = "[$timestamp] ${reaction.name}: $reactionText"
        reactionHistory.add(0, historyEntry) // 添加到开头
        if (reactionHistory.size > 10) { // 保持最多10条记录
            reactionHistory.removeAt(reactionHistory.size - 1)
        }
        
        // 更新状态文本，包含最新反应和历史
        val displayText = buildString {
            append("🧪 ${reaction.name}\n")
            append("${reaction.description}\n")
            append("━━━━━━━━━━━━━━━━━━━━\n")
            append("📋 反应历史:\n")
            reactionHistory.take(3).forEach { entry ->
                append("• $entry\n")
            }
        }
        reactionStatusText.text = displayText.trimEnd()
        
        // 添加颜色变化效果
        reactionStatusText.setTextColor(getReactionColor(reaction))
        
        // 添加缩放动画
        reactionStatusText.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(200)
            .withEndAction {
                reactionStatusText.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .start()
            }
            .start()
        
        // 显示Toast通知
        val toastMessage = "🔬 ${reaction.name}: $reactionText"
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show()
        
        // 记录详细信息
        Log.i(TAG, "Chemical reaction detected: ${reaction.name}")
        Log.i(TAG, "Reactants: ${reactantElements.map { "${it.type}(${it.color})" }}")
        Log.i(TAG, "Reaction equation: ${reaction.description}")
        Log.i(TAG, "Total reactions in history: ${reactionHistory.size}")
    }
    
    private fun getChemicalDisplayName(type: ChemicalType): String {
        return when (type) {
            ChemicalType.Na -> "Na"
            ChemicalType.H2O -> "H₂O"
            ChemicalType.H2 -> "H₂"
            ChemicalType.O2 -> "O₂"
            ChemicalType.NaOH -> "NaOH"
            ChemicalType.HCl -> "HCl"
            ChemicalType.Cl2 -> "Cl₂"
            ChemicalType.CO2 -> "CO₂"
            ChemicalType.C -> "C"
        }
    }
    
    private fun getReactionColor(reaction: ChemicalReaction): Int {
        return when (reaction.name) {
            "钠与水反应" -> Color.parseColor("#FF6B35") // 橙红色，表示剧烈反应
            "氢气燃烧" -> Color.parseColor("#FF4444") // 红色，表示燃烧
            "钠氧化反应" -> Color.parseColor("#FFA500") // 橙色，表示氧化
            "水的混合", "氢气混合", "氧气混合" -> Color.parseColor("#4CAF50") // 绿色，表示混合
            else -> Color.parseColor("#2196F3") // 蓝色，默认颜色
        }
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