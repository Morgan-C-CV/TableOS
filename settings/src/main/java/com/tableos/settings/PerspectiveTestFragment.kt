package com.tableos.settings

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class PerspectiveTestFragment : Fragment() {
    
    companion object {
        private const val TAG = "PerspectiveTestFragment"
        private const val REQUEST_CAMERA_PERMISSION = 1
    }
    
    private lateinit var textureView: TextureView
    private lateinit var ivCorrected: ImageView
    private lateinit var btnStartPreview: Button
    private lateinit var btnStopPreview: Button
    private lateinit var tvStatus: TextView

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)

    private var previewSize: Size? = null
    private var imageReader: ImageReader? = null

    // 透视变换相关
    private var perspectiveMatrix: Matrix? = null
    private var keystonePoints: FloatArray? = null
    private var isProcessingEnabled = false
    private var correctedBitmap: Bitmap? = null
    private var canvas: Canvas? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_perspective_test, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textureView = view.findViewById(R.id.texture_view)
        ivCorrected = view.findViewById(R.id.iv_corrected)
        btnStartPreview = view.findViewById(R.id.btn_start_preview)
        btnStopPreview = view.findViewById(R.id.btn_stop_preview)
        tvStatus = view.findViewById(R.id.tv_status)

        btnStartPreview.setOnClickListener {
            startPreview()
        }

        btnStopPreview.setOnClickListener {
            stopPreview()
        }

        // 加载保存的边框坐标
        loadKeystonePoints()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // 配置变换矩阵以修正预览方向
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            // 在这里对每一帧进行透视变换处理
            if (isProcessingEnabled && perspectiveMatrix != null) {
                processPerspectiveCorrection(surface)
            }
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    private fun openCamera(width: Int, height: Int) {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            return
        }

        val manager = requireContext().getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            val cameraId = manager.cameraIdList[0]
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            
            previewSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                width, height, Size(width, height)
            )

            // 创建 ImageReader 用于获取图像数据
            imageReader = ImageReader.newInstance(
                previewSize!!.width,
                previewSize!!.height,
                ImageFormat.YUV_420_888,
                1
            )

            manager.openCamera(cameraId, stateCallback, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access camera", e)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCameraPreviewSession()
            updateStatus("相机已打开")
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            updateStatus("相机已断开")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            updateStatus("相机错误: $error")
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture!!
            texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

            val surface = Surface(texture)
            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(surface)

            cameraDevice!!.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return

                        captureSession = session
                        try {
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                            )

                            val previewRequest = previewRequestBuilder.build()
                            session.setRepeatingRequest(
                                previewRequest,
                                null,
                                backgroundHandler
                            )
                            
                            // 配置TextureView的变换矩阵以修正预览方向
                            configureTransform(textureView.width, textureView.height)
                            
                            updateStatus("预览会话已创建")
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Failed to set up camera preview", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        updateStatus("预览会话配置失败")
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create camera preview session", e)
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun startPreview() {
        if (keystonePoints == null) {
            Toast.makeText(requireContext(), "请先在视频输入矫正中设置边框", Toast.LENGTH_SHORT).show()
            return
        }
        
        calculatePerspectiveMatrix()
        isProcessingEnabled = true
        updateStatus("透视矫正预览已启动")
    }

    private fun stopPreview() {
        isProcessingEnabled = false
        updateStatus("透视矫正预览已停止")
    }

    private fun loadKeystonePoints() {
        try {
            Log.d(TAG, "开始加载边框坐标...")
            val uri = Uri.parse("content://com.tableos.app.keystone/input_region")
            Log.d(TAG, "使用 URI: $uri")
            
            val cursor = requireContext().contentResolver.query(
                uri, arrayOf("value"), null, null, null
            )
            Log.d(TAG, "ContentResolver.query 返回: ${cursor != null}")
            
            cursor?.use { c ->
                Log.d(TAG, "Cursor 行数: ${c.count}")
                if (c.moveToFirst()) {
                    val csvData = c.getString(0)
                    Log.d(TAG, "获取到 CSV 数据: $csvData")
                    if (!csvData.isNullOrBlank()) {
                        keystonePoints = parseCsv(csvData)
                        Log.d(TAG, "解析坐标成功: ${keystonePoints?.size} 个点")
                        updateStatus("已加载边框坐标: $csvData")
                    } else {
                        Log.w(TAG, "CSV 数据为空")
                        updateStatus("边框数据为空")
                    }
                } else {
                    Log.w(TAG, "Cursor 中没有数据")
                    updateStatus("未找到保存的边框坐标")
                }
            } ?: run {
                Log.e(TAG, "ContentResolver.query 返回 null")
                updateStatus("无法访问边框数据")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load keystone points", e)
            updateStatus("加载边框坐标失败: ${e.message}")
        }
    }

    private fun parseCsv(csvData: String): FloatArray {
        Log.d(TAG, "开始解析 CSV: $csvData")
        val points = csvData.split(";")
        Log.d(TAG, "分割后的部分数量: ${points.size}, 内容: ${points.joinToString()}")
        
        if (points.size != 4) {
            Log.e(TAG, "CSV 格式错误: 期望 4 个部分，实际 ${points.size} 个")
            throw IllegalArgumentException("CSV 格式错误: 期望 4 个部分，实际 ${points.size} 个")
        }
        
        val result = FloatArray(8)
        
        for (i in points.indices) {
            val coords = points[i].split(",")
            Log.d(TAG, "解析第 $i 个点: ${points[i]} -> ${coords.joinToString()}")
            
            if (coords.size != 2) {
                Log.e(TAG, "坐标格式错误: 期望 2 个值，实际 ${coords.size} 个")
                throw IllegalArgumentException("坐标格式错误: 期望 2 个值，实际 ${coords.size} 个")
            }
            
            result[i * 2] = coords[0].toFloat()
            result[i * 2 + 1] = coords[1].toFloat()
            Log.d(TAG, "第 $i 个点坐标: (${result[i * 2]}, ${result[i * 2 + 1]})")
        }
        
        Log.d(TAG, "CSV 解析成功，共 ${points.size} 个点")
        Log.d(TAG, "Keystone points as coordinates:")
        Log.d(TAG, "  左上角: (${result[0]}, ${result[1]})")
        Log.d(TAG, "  右上角: (${result[2]}, ${result[3]})")
        Log.d(TAG, "  右下角: (${result[4]}, ${result[5]})")
        Log.d(TAG, "  左下角: (${result[6]}, ${result[7]})")
        return result
    }

    private fun calculatePerspectiveMatrix() {
        if (keystonePoints == null || previewSize == null) return

        // 源坐标：边框的四个角点（在原图像中的实际位置）
        val src = FloatArray(8)
        // 将归一化坐标转换为实际像素坐标
        for (i in 0 until 4) {
            src[i * 2] = keystonePoints!![i * 2] * previewSize!!.width
            src[i * 2 + 1] = keystonePoints!![i * 2 + 1] * previewSize!!.height
        }

        // 目标坐标：矫正后的矩形（填充整个显示区域）
        val dst = floatArrayOf(
            0f, 0f,                                    // 左上
            previewSize!!.width.toFloat(), 0f,         // 右上
            previewSize!!.width.toFloat(), previewSize!!.height.toFloat(), // 右下
            0f, previewSize!!.height.toFloat()         // 左下
        )

        perspectiveMatrix = Matrix()
        perspectiveMatrix!!.setPolyToPoly(src, 0, dst, 0, 4)
        
        Log.d(TAG, "Perspective matrix calculated - src: [${src.joinToString()}], dst: [${dst.joinToString()}]")
        updateStatus("透视变换矩阵已计算")
    }

    private fun chooseOptimalSize(
        choices: Array<Size>,
        textureViewWidth: Int,
        textureViewHeight: Int,
        maxSize: Size
    ): Size {
        val bigEnough = mutableListOf<Size>()
        val notBigEnough = mutableListOf<Size>()
        val w = maxSize.width
        val h = maxSize.height
        
        for (option in choices) {
            if (option.width <= w && option.height <= h) {
                if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        return when {
            bigEnough.isNotEmpty() -> bigEnough.minByOrNull { it.width * it.height }!!
            notBigEnough.isNotEmpty() -> notBigEnough.maxByOrNull { it.width * it.height }!!
            else -> choices[0]
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()
        if (previewSize != null) {
            val rotation = requireActivity().windowManager.defaultDisplay.rotation
            
            // 根据设备方向计算需要的旋转角度
            val rotationDegrees = when (rotation) {
                Surface.ROTATION_0 -> 90f    // 竖屏，相机需要旋转90度
                Surface.ROTATION_90 -> 0f    // 横屏左，不需要旋转
                Surface.ROTATION_180 -> 270f // 倒竖屏，相机需要旋转270度
                Surface.ROTATION_270 -> 180f // 横屏右，相机需要旋转180度
                else -> 90f
            }
            
            matrix.postRotate(rotationDegrees, viewWidth / 2f, viewHeight / 2f)
            
            // 应用变换矩阵到TextureView
            textureView.setTransform(matrix)
            Log.d(TAG, "Applied camera transform: rotation=${rotationDegrees}°, deviceRotation=$rotation, viewSize=${viewWidth}x${viewHeight}")
        }
    }

    private fun updateStatus(status: String) {
        requireActivity().runOnUiThread {
            tvStatus.text = "状态：$status"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera(textureView.width, textureView.height)
            } else {
                Toast.makeText(requireContext(), "需要相机权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processPerspectiveCorrection(surface: SurfaceTexture) {
        try {
            // 获取当前帧的 Bitmap
            val originalBitmap = textureView.getBitmap() ?: return
            
            // 由于TextureView已经应用了旋转变换，从getBitmap()获取的图像应该是正确方向的
            // 但为了确保透视矫正的输出是全屏的，我们需要使用屏幕尺寸
            val screenWidth = textureView.width
            val screenHeight = textureView.height
            
            // 创建矫正后的 Bitmap（使用屏幕尺寸）
            if (correctedBitmap == null || 
                correctedBitmap!!.width != screenWidth || 
                correctedBitmap!!.height != screenHeight) {
                correctedBitmap = Bitmap.createBitmap(
                    screenWidth, 
                    screenHeight, 
                    Bitmap.Config.ARGB_8888
                )
                canvas = Canvas(correctedBitmap!!)
            }

            // 清除画布
            canvas!!.drawColor(Color.BLACK)
            
            // 应用透视变换
            perspectiveMatrix?.let { matrix ->
                // 创建一个Paint对象来控制绘制质量
                val paint = Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                    isDither = true
                }
                
                // 应用透视变换，将边框区域矫正为矩形并填充整个画布
                canvas!!.drawBitmap(originalBitmap, matrix, paint)
                
                Log.d(TAG, "Applied perspective transformation with matrix: $matrix, output size: ${screenWidth}x${screenHeight}")
            }

            // 将矫正后的图像显示在 ImageView 中
            requireActivity().runOnUiThread {
                if (correctedBitmap != null && !correctedBitmap!!.isRecycled) {
                    ivCorrected.setImageBitmap(correctedBitmap)
                    Log.d(TAG, "Perspective correction applied and displayed")
                }
            }

            originalBitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error in perspective correction", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        correctedBitmap?.recycle()
        correctedBitmap = null
        canvas = null
    }
}