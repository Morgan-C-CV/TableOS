package com.tableos.beakerlab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.app.ActivityCompat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CameraManager(private val context: Context) {
    
    private val TAG = "CameraManager"
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var textureView: TextureView? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    
    // 帧率控制
    private val targetFps = 20
    private val frameInterval = 1000L / targetFps // 50ms per frame
    private var lastFrameTime = 0L
    
    // 线程安全
    private val cameraOpenCloseLock = Semaphore(1)
    
    // 回调接口
    interface FrameCallback {
        fun onFrameAvailable(image: Image)
    }
    
    private var frameCallback: FrameCallback? = null
    
    // Camera state callback
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened")
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCameraPreviewSession()
        }
        
        override fun onDisconnected(camera: CameraDevice) {
            Log.d(TAG, "Camera disconnected")
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }
        
        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }
    }
    
    // Image reader callback with frame rate limiting
    private val imageReaderListener = ImageReader.OnImageAvailableListener { reader ->
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime >= frameInterval) {
            val image = reader.acquireLatestImage()
            if (image != null) {
                frameCallback?.onFrameAvailable(image)
                lastFrameTime = currentTime
                image.close()
            }
        } else {
            // Skip frame to maintain target FPS
            reader.acquireLatestImage()?.close()
        }
    }
    
    fun setFrameCallback(callback: FrameCallback) {
        this.frameCallback = callback
    }
    
    fun setTextureView(textureView: TextureView) {
        this.textureView = textureView
    }
    
    fun startCamera() {
        startBackgroundThread()
        
        if (textureView?.isAvailable == true) {
            openCamera()
        } else {
            textureView?.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera()
                }
                
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    // 当TextureView大小改变时，重新配置变换矩阵
                    configureTransform(width, height)
                }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }
    
    fun stopCamera() {
        closeCamera()
        stopBackgroundThread()
    }
    
    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted")
            return
        }
        
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        
        try {
            val cameraId = manager.cameraIdList[0] // Use back camera
            val characteristics = manager.getCameraCharacteristics(cameraId)
            cameraCharacteristics = characteristics // 保存相机特性
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            // Choose appropriate size for image processing
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            val chosenSize = chooseOptimalSize(sizes)
            
            // Setup ImageReader for frame processing
            imageReader = ImageReader.newInstance(chosenSize.width, chosenSize.height, ImageFormat.YUV_420_888, 2)
            imageReader?.setOnImageAvailableListener(imageReaderListener, backgroundHandler)
            
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while trying to lock camera opening", e)
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
            Log.e(TAG, "Interrupted while trying to lock camera closing", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }
    
    private fun createCameraPreviewSession() {
        try {
            val texture = textureView?.surfaceTexture
            texture?.setDefaultBufferSize(640, 480)
            val surface = Surface(texture)
            
            val surfaces = mutableListOf<Surface>()
            surfaces.add(surface)
            imageReader?.surface?.let { surfaces.add(it) }
            
            cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    
                    captureSession = session
                    try {
                        val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        captureRequestBuilder.addTarget(surface)
                        imageReader?.surface?.let { captureRequestBuilder.addTarget(it) }
                        
                        // Set auto focus
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        
                        // 自动曝光设置 - 增加曝光补偿
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                        
                        // 曝光补偿设置 - 增加曝光，提高亮度
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 4)
                        
                        // ISO设置 - 提高ISO增加感光度
                        captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 600)
                        
                        // 白平衡设置 - 使用自动白平衡
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                        
                        // 场景模式设置为自动
                        captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        
                        Log.d(TAG, "Applied camera white balance and exposure settings")
                        
                        val captureRequest = captureRequestBuilder.build()
                        captureSession?.setRepeatingRequest(captureRequest, null, backgroundHandler)
                        
                        // 配置TextureView的变换矩阵以修正预览方向
                        textureView?.let { tv ->
                            configureTransform(tv.width, tv.height)
                        }
                        
                        Log.d(TAG, "Camera preview session created")
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Camera access exception in preview session", e)
                    }
                }
                
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera preview session configuration failed")
                }
            }, null)
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
        }
    }
    
    private fun chooseOptimalSize(choices: Array<Size>?): Size {
        if (choices == null) return Size(640, 480)
        
        // Prefer 640x480 for better performance
        for (size in choices) {
            if (size.width == 640 && size.height == 480) {
                return size
            }
        }
        
        // Fallback to first available size
        return choices.firstOrNull() ?: Size(640, 480)
    }
    
    private fun computeTotalRotation(): Int {
        val characteristics = cameraCharacteristics ?: return 0
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        
        // 获取显示旋转
        val displayRotation = if (context is android.app.Activity) {
            context.windowManager.defaultDisplay.rotation
        } else {
            Surface.ROTATION_0
        }
        
        val display = when (displayRotation) {
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
        val textureView = this.textureView ?: return
        if (viewWidth == 0 || viewHeight == 0) return
        
        val rotation = computeTotalRotation()
        val matrix = Matrix()
        
        // 根据计算出的旋转角度设置变换矩阵
        // 如果相机传感器是90度，我们需要逆向旋转270度来修正
        val rotationDegrees = when (rotation) {
            90 -> 270f  // 改为270度来修正90度的旋转
            180 -> 180f
            270 -> 90f  // 改为90度来修正270度的旋转
            else -> 0f
        }
        
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        
        if (rotationDegrees != 0f) {
            // 先旋转
            matrix.postRotate(rotationDegrees, centerX, centerY)
            
            // 计算旋转后需要的缩放比例来填满视图并消除白边
            if (rotationDegrees == 90f || rotationDegrees == 270f) {
                // 90度或270度旋转时，宽高互换
                val scaleX = viewHeight.toFloat() / viewWidth.toFloat()
                val scaleY = viewWidth.toFloat() / viewHeight.toFloat()
                
                // 使用较大的缩放比例来确保填满整个视图
                val scale = maxOf(scaleX, scaleY)
                matrix.postScale(scale, scale, centerX, centerY)
                
                Log.d(TAG, "Applied scaling: scaleX=$scaleX, scaleY=$scaleY, finalScale=$scale")
            }
        }
        
        // 应用变换矩阵到TextureView
        textureView.setTransform(matrix)
        Log.d(TAG, "Applied camera transform: rotation=${rotationDegrees}°, viewSize=${viewWidth}x${viewHeight}")
    }
    
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }
    
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while stopping background thread", e)
        }
    }
}