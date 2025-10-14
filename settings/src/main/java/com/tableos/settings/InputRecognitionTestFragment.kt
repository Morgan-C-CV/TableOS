package com.tableos.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.graphics.ImageFormat
import android.os.Bundle
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.nio.ByteBuffer

class InputRecognitionTestFragment : Fragment() {
    private lateinit var textureView: TextureView
    private lateinit var overlay: RecognitionOverlayView
    private lateinit var resultText: TextView

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

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            startPreviewWhenReady()
        }
    }

    private fun startPreviewWhenReady() {
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera()
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { return true }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
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
        val surfaceTexture = textureView.surfaceTexture ?: return
        // 优先选择较小且支持的 YUV 输出尺寸，避免高分辨率导致卡顿
        var (width, height) = chooseYuvSize() ?: Pair(640, 480)
        // YUV_420_888/NV21 要求偶数尺寸，必要时做向下取偶
        if ((width and 1) == 1) width -= 1
        if ((height and 1) == 1) height -= 1
        previewWidth = width
        previewHeight = height
        // 计算需要应用到 TextureView 的旋转角度，保证显示正向且比例不失真
        runCatching {
            val manager = requireContext().getSystemService(CameraManager::class.java)
            val characteristics = manager.getCameraCharacteristics(device.id)
            appliedRotation = computeTotalRotation(characteristics)
        }.onFailure { appliedRotation = 0 }

        surfaceTexture.setDefaultBufferSize(previewWidth, previewHeight)
        val previewSurface = Surface(surfaceTexture)

        // ImageReader for YUV_420_888 frames
        imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.nanoTime()
            if (!processing && now - lastProcessNs > 80_000_000) { // ~12.5 FPS 节流
                processing = true
                lastProcessNs = now
                try { processImage(image) } catch (_: Exception) {} finally { processing = false }
            }
            image.close()
        }, backgroundHandler)

        try {
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                imageReader?.surface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            val targets = mutableListOf<Surface>(previewSurface)
            imageReader?.surface?.let { targets.add(it) }
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try { previewRequestBuilder?.build()?.let { session.setRepeatingRequest(it, null, backgroundHandler) } } catch (_: Exception) {}
                    // 根据当前视图大小与预览缓冲尺寸，应用矩阵变换，确保不拉伸且按需旋转
                    runCatching { configureTransform(textureView.width, textureView.height) }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(requireContext(), "预览会话创建失败", Toast.LENGTH_SHORT).show()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "创建预览异常：${e.message}", Toast.LENGTH_SHORT).show()
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
        if (previewWidth == 0 || previewHeight == 0) return
        val rotation = appliedRotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        // 缓冲区在 90/270 度时宽高对调
        val bufferRect = if (rotation == 90 || rotation == 270) {
            RectF(0f, 0f, previewHeight.toFloat(), previewWidth.toFloat())
        } else {
            RectF(0f, 0f, previewWidth.toFloat(), previewHeight.toFloat())
        }
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (rotation == 90 || rotation == 270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = kotlin.math.max(
                viewHeight.toFloat() / previewHeight.toFloat(),
                viewWidth.toFloat() / previewWidth.toFloat()
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(rotation.toFloat(), centerX, centerY)
        } else if (rotation == 180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    private fun chooseYuvSize(): Pair<Int, Int>? {
        val device = cameraDevice ?: return null
        val manager = requireContext().getSystemService(CameraManager::class.java)
        val characteristics = try { manager.getCameraCharacteristics(device.id) } catch (_: Exception) { return null }
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
        // 提升分辨率：优先选择不超过 1920x1080 的最大尺寸，否则选择支持的最大尺寸
        val candidates = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        val ch = (candidates.maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }) ?: return null
        var w = ch.width; var h = ch.height
        if ((w and 1) == 1) w -= 1
        if ((h and 1) == 1) h -= 1
        return Pair(w, h)
    }

    private fun processImage(image: Image) {
        val width = image.width
        val height = image.height
        val nv21 = yuv420ToNv21(image)

        val out = ProjectionCardsBridge.detectNv21Safe(nv21, width, height, 8)
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
        val sx = textureView.width.toFloat() / dispW
        val sy = textureView.height.toFloat() / dispH
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
        closeCamera()
        stopBackgroundThread()
    }

    override fun onDestroyView() {
        super.onDestroyView()
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