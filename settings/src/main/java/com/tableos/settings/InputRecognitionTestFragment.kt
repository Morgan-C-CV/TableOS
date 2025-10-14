package com.tableos.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.media.Image
import android.media.ImageReader
import android.util.Rational
import android.graphics.YuvImage
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.graphics.ImageFormat
import android.os.Bundle
import android.view.*
import android.widget.TextView
import android.widget.Toast
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class InputRecognitionTestFragment : Fragment() {
    private lateinit var textureView: TextureView
    private lateinit var overlay: RecognitionOverlayView
    private lateinit var resultText: TextView

    private var warpLayout: KeystoneWarpLayout? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    @Volatile private var lastProcessNs: Long = 0L
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0
    private var appliedRotation: Int = 0
    // 启动识别时抓拍一张保存到相册
    @Volatile private var snapshotPending: Boolean = true
    @Volatile private var snapshotSaved: Boolean = false

    private val REQUEST_CAMERA = 1101
    @Volatile private var processing = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_input_recognition_test, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        textureView = view.findViewById(R.id.texture_view)
        overlay = view.findViewById(R.id.overlay_view)
        resultText = view.findViewById(R.id.result_text)

        // 禁用设置页根布局的 Keystone 变形，避免相机预览被二次变形造成边缘留白/梯形
        warpLayout = requireActivity().findViewById(R.id.keystone_root)
        warpLayout?.setWarpEnabled(false)

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            startPreviewWhenReady()
        }
    }

    override fun onResume() {
        super.onResume()
        // 进入识别页时确保禁用显示矫正
        warpLayout = requireActivity().findViewById(R.id.keystone_root)
        warpLayout?.setWarpEnabled(false)
    }

    private fun startPreviewWhenReady() {
        // 不需要预览，直接打开摄像头用于识别
        openCamera()
    }

    private fun openCamera() {
        val manager = requireContext().getSystemService(CameraManager::class.java)
        val cameraId = chooseCamera(manager)
        if (cameraId == null) { Toast.makeText(requireContext(), "未找到可用摄像头", Toast.LENGTH_SHORT).show(); return }
        try {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            startBackgroundThread()
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { cameraDevice = device; createPreviewSession() }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
                override fun onError(device: CameraDevice, error: Int) { device.close(); cameraDevice = null; Toast.makeText(requireContext(), "摄像头打开失败：$error", Toast.LENGTH_SHORT).show() }
            }, backgroundHandler)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "打开摄像头异常：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseCamera(manager: CameraManager): String? {
        return try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
        } catch (_: Exception) { null }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        // 仅使用离屏 ImageReader，不创建可见预览 Surface
        var (width, height) = chooseYuvSize() ?: Pair(640, 480)
        if ((width and 1) == 1) width -= 1
        if ((height and 1) == 1) height -= 1

        runCatching {
            val manager = requireContext().getSystemService(CameraManager::class.java)
            val characteristics = manager.getCameraCharacteristics(device.id)
            appliedRotation = computeTotalRotation(characteristics)
        }.onFailure { appliedRotation = 0 }

        imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.nanoTime()
            // 首帧抓拍保存到相册（仅一次）
            if (snapshotPending && !snapshotSaved) {
                runCatching {
                    saveImageToGallery(image)
                    snapshotSaved = true
                    snapshotPending = false
                }.onFailure {
                    Log.w("IRTest", "saveImageToGallery failed: ${it.message}")
                }
            }
            if (!processing && now - lastProcessNs > 80_000_000) {
                processing = true
                lastProcessNs = now
                try { processImage(image) } catch (_: Exception) {} finally { processing = false }
            }
            image.close()
        }, backgroundHandler)

        try {
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                imageReader?.surface?.let { addTarget(it) }
                
                // 自动对焦设置
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                
                // 自动曝光设置 - 添加曝光补偿以减少过曝
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                
                // 曝光补偿设置 - 大幅增加曝光，显著提高亮度
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 6)
                
                // ISO设置 - 大幅提高ISO增加感光度和亮度
                set(CaptureRequest.SENSOR_SENSITIVITY, 800)
                
                // 白平衡设置 - 使用荧光灯模式以减少色差
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)
                set(CaptureRequest.CONTROL_AWB_LOCK, false)
                
                // 场景模式设置为自动
                set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                
                // 图像稳定设置
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                
                // 降噪设置 - 使用快速模式以减少色彩失真
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                
                // 边缘增强设置 - 使用快速模式避免过度处理
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                
                // 色彩校正设置 - 使用变换矩阵模式以获得更准确的色彩
                set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                
                // 设置色彩变换矩阵以极大增加饱和度、对比度和改善黄色可见性
                val colorTransform = ColorSpaceTransform(arrayOf(
                    Rational(140, 100), Rational(15, 100), Rational(0, 100),    // R (极大增强红色分量，显著提高饱和度)
                    Rational(15, 100), Rational(140, 100), Rational(0, 100),    // G (极大增强绿色分量，显著提高饱和度)
                    Rational(0, 100), Rational(0, 100), Rational(75, 100)       // B (大幅减少蓝色分量，极大增加对比度)
                ))
                set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, colorTransform)
                
                Log.i("IRTest", "Applied camera optimization parameters")
            }

            val targets = mutableListOf<Surface>()
            imageReader?.surface?.let { targets.add(it) }
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try { previewRequestBuilder?.build()?.let { session.setRepeatingRequest(it, null, backgroundHandler) } } catch (_: Exception) {}
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(requireContext(), "会话创建失败", Toast.LENGTH_SHORT).show()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "创建会话异常：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 将当前帧转为 JPEG 并保存到相册（MediaStore）
    private fun saveImageToGallery(image: Image) {
        val width = image.width
        val height = image.height
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val jpegOut = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 90, jpegOut)
        val bytes = jpegOut.toByteArray()

        val resolver = requireContext().contentResolver
        val fileName = "TableOS_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TableOS")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, cv, null, null)
            }
            Log.i("IRTest", "Saved snapshot to gallery: $uri (${width}x${height})")
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), "已保存照片至相册", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.w("IRTest", "Failed to insert into MediaStore")
        }
    }

    private fun computeTotalRotation(characteristics: CameraCharacteristics): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val displayRotEnum = requireActivity().display?.rotation ?: Surface.ROTATION_0
        val display = when (displayRotEnum) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + display) % 360
        } else {
            (sensor - display + 360) % 360
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        // 简化并增强鲁棒性：使用视图旋转属性，避免复杂矩阵导致内容完全移出视域
        if (viewWidth == 0 || viewHeight == 0 || previewWidth == 0 || previewHeight == 0) {
            // 视图尚未布局或预览尺寸未知，重置为默认
            textureView.setTransform(null)
            textureView.rotation = 0f
            return
        }
        val rotation = appliedRotation
        // 设定默认缓冲大小匹配当前预览尺寸，避免缩放失真
        textureView.setTransform(null)
        // 使用视图层旋转，系统负责内容居中与裁剪
        textureView.rotation = when (rotation) {
            90 -> 90f
            180 -> 180f
            270 -> 270f
            else -> 0f
        }
    }

    private fun chooseYuvSize(): Pair<Int, Int>? {
        val device = cameraDevice ?: return null
        val manager = requireContext().getSystemService(CameraManager::class.java)
        val characteristics = try { manager.getCameraCharacteristics(device.id) } catch (_: Exception) { return null }
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
        
        // 提高分辨率以获得更好的图像质量：优先选择高分辨率
        val preferredSizes = listOf(
            Pair(1920, 1080),  // Full HD
            Pair(1600, 1200),  // UXGA
            Pair(1280, 960),   // SXGA
            Pair(1280, 720),   // HD
            Pair(1024, 768)    // XGA
        )
        
        // 查找最接近的支持分辨率
        for (preferred in preferredSizes) {
            val match = sizes.find { 
                it.width == preferred.first && it.height == preferred.second 
            }
            if (match != null) {
                Log.i("IRTest", "Selected resolution: ${match.width}x${match.height} (preferred)")
                return Pair(match.width, match.height)
            }
        }
        
        // 如果没有找到首选分辨率，选择不超过 1920x1080 的最大尺寸
        val candidates = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        val ch = (candidates.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }) ?: return null
        var w = ch.width; var h = ch.height
        if ((w and 1) == 1) w -= 1
        if ((h and 1) == 1) h -= 1
        Log.i("IRTest", "Selected resolution: ${w}x${h} (fallback)")
        return Pair(w, h)
    }

    private fun processImage(image: Image) {
        val width = image.width
        val height = image.height
        val nv21 = yuv420ToNv21(image)
        
        // 应用图像预处理以减少摩尔纹和色偏
        val processedNv21 = preprocessImage(nv21, width, height)

        val out = ProjectionCardsBridge.detectNv21Safe(processedNv21, width, height, 8)
        val count = if (out.isNotEmpty()) out[0] else 0
        if (count <= 0) {
            requireActivity().runOnUiThread {
                resultText.text = "识别结果：未检测到卡片"
                overlay.showBoxes(emptyList())
            }
            return
        }

        val boxes = mutableListOf<RectF>()
        val sb = StringBuilder()
        sb.append("识别结果：共").append(count).append("张\n")
        // 根据旋转后的显示尺寸进行缩放计算
        val dispW = if (appliedRotation == 90 || appliedRotation == 270) height else width
        val dispH = if (appliedRotation == 90 || appliedRotation == 270) width else height
        val sx = overlay.width.toFloat() / dispW
        val sy = overlay.height.toFloat() / dispH
        for (i in 0 until count) {
            val base = 1 + i * 6
            val cardId = out[base]
            val group = out[base + 1]
            val tlx = out[base + 2]
            val tly = out[base + 3]
            val brx = out[base + 4]
            val bry = out[base + 5]
            sb.append("#").append(i + 1).append(" ID=").append(cardId)
                .append(" 组=").append(if (group == 0) "A" else if (group == 1) "B" else "?")
                .append(" 位置=(").append(tlx).append(",").append(tly).append(")-(").append(brx).append(",").append(bry).append(")\n")
            var rect = RectF(tlx.toFloat(), tly.toFloat(), brx.toFloat(), bry.toFloat())
            rect = transformRectForRotation(rect, width, height, appliedRotation)
            boxes.add(RectF(rect.left * sx, rect.top * sy, rect.right * sx, rect.bottom * sy))
        }

        requireActivity().runOnUiThread {
            resultText.text = sb.toString()
            overlay.showBoxes(boxes)
        }
    }

    private fun transformRectForRotation(rect: RectF, w: Int, h: Int, rotation: Int): RectF {
        if (rotation == 0) return RectF(rect)
        val corners = arrayOf(
            floatArrayOf(rect.left, rect.top),
            floatArrayOf(rect.right, rect.top),
            floatArrayOf(rect.right, rect.bottom),
            floatArrayOf(rect.left, rect.bottom)
        )
        val tx = FloatArray(4)
        val ty = FloatArray(4)
        for (i in 0..3) {
            val x = corners[i][0]
            val y = corners[i][1]
            when (rotation) {
                90 -> { tx[i] = y; ty[i] = (w - x) }
                180 -> { tx[i] = (w - x); ty[i] = (h - y) }
                270 -> { tx[i] = (h - y); ty[i] = x }
                else -> { tx[i] = x; ty[i] = y }
            }
        }
        val minX = tx.minOrNull() ?: 0f
        val maxX = tx.maxOrNull() ?: 0f
        val minY = ty.minOrNull() ?: 0f
        val maxY = ty.maxOrNull() ?: 0f
        return RectF(minX, minY, maxX, maxY)
    }

    private fun preprocessImage(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val processedNv21 = nv21.copyOf()
        
        // 对Y分量进行轻微的高斯模糊以减少摩尔纹
        applyGaussianBlurToY(processedNv21, width, height)
        
        // 对Y分量进行对比度和亮度调整以改善识别效果
        adjustContrastAndBrightness(processedNv21, width, height)
        
        // 调整UV分量以减少色差
        adjustUVColorBalance(processedNv21, width, height)
        
        return processedNv21
    }
    
    private fun applyGaussianBlurToY(nv21: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        val temp = ByteArray(ySize)
        
        // 简单的3x3高斯核，权重为 [1,2,1; 2,4,2; 1,2,1] / 16
        val kernel = intArrayOf(1, 2, 1, 2, 4, 2, 1, 2, 1)
        val kernelSum = 16
        
        // 只对Y分量应用模糊
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var sum = 0
                var kernelIndex = 0
                
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixelIndex = (y + ky) * width + (x + kx)
                        val pixelValue = nv21[pixelIndex].toInt() and 0xFF
                        sum += pixelValue * kernel[kernelIndex]
                        kernelIndex++
                    }
                }
                
                temp[y * width + x] = (sum / kernelSum).coerceIn(0, 255).toByte()
            }
        }
        
        // 复制处理后的Y分量回原数组（保留边界像素不变）
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                nv21[y * width + x] = temp[y * width + x]
            }
        }
    }
    
    private fun adjustContrastAndBrightness(nv21: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        
        // 提高对比度和亮度以改善图像质量
        val contrast = 1.3f   // 大幅提高对比度因子
        val brightness = 15   // 显著增加亮度调整
        
        for (i in 0 until ySize) {
            val originalValue = nv21[i].toInt() and 0xFF
            val adjustedValue = ((originalValue - 128) * contrast + 128 + brightness)
                .coerceIn(0.0f, 255.0f).toInt()
            nv21[i] = adjustedValue.toByte()
        }
    }
    
    private fun adjustUVColorBalance(nv21: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        val uvSize = ySize / 2
        
        // UV分量从Y分量之后开始
        val uvStart = ySize
        
        // 调整UV分量以增加饱和度和改善色彩表现
        // U分量调整（蓝色-黄色轴）- 适度调整以增加饱和度
        // V分量调整（红色-绿色轴）- 增强红色分量以提高饱和度
        val uAdjust = -5  // 适度减少蓝色偏移，增加饱和度
        val vAdjust = 8   // 显著增加红色分量以提高饱和度
        
        for (i in 0 until uvSize step 2) {
            val uIndex = uvStart + i
            val vIndex = uvStart + i + 1
            
            if (uIndex < nv21.size && vIndex < nv21.size) {
                // 调整U分量
                val uValue = (nv21[uIndex].toInt() and 0xFF) + uAdjust
                nv21[uIndex] = uValue.coerceIn(0, 255).toByte()
                
                // 调整V分量
                val vValue = (nv21[vIndex].toInt() and 0xFF) + vAdjust
                nv21[vIndex] = vValue.coerceIn(0, 255).toByte()
            }
        }
        
        Log.i("IRTest", "Applied UV color balance: U=$uAdjust, V=$vAdjust")
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Copy Y plane
        val yBuffer = yPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var outIndex = 0
        for (row in 0 until height) {
            var inputIndex = row * yRowStride
            for (col in 0 until width) {
                nv21[outIndex++] = yBuffer.get(inputIndex + col * yPixelStride)
            }
        }

        // Interleave V and U for NV21
        val vBuffer = vPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vRowStride = vPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val vRowStart = row * vRowStride
            val uRowStart = row * uRowStride
            for (col in 0 until chromaWidth) {
                val vIndex = vRowStart + col * vPixelStride
                val uIndex = uRowStart + col * uPixelStride
                nv21[outIndex++] = vBuffer.get(vIndex)
                nv21[outIndex++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startPreviewWhenReady()
            } else {
                Toast.makeText(requireContext(), "需要摄像头权限以进行识别测试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开页面时恢复显示矫正
        warpLayout?.setWarpEnabled(true)
        closeCamera()
        stopBackgroundThread()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 销毁视图后进一步恢复显示矫正
        warpLayout?.setWarpEnabled(true)
        warpLayout = null
        closeCamera()
        stopBackgroundThread()
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        imageReader?.close(); imageReader = null
    }

    private fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true) return
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join()
        } catch (_: Exception) {}
        backgroundThread = null
        backgroundHandler = null
    }
}