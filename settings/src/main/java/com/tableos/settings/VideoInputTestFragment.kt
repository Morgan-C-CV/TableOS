package com.tableos.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import android.app.AlertDialog
import android.content.ContentValues
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.addCallback

class VideoInputTestFragment : Fragment() {
    private lateinit var textureView: TextureView
    private lateinit var calibrator: KeystoneCalibratorView
    private var warpLayout: KeystoneWarpLayout? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0

    private val REQUEST_CAMERA = 1001

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_video_input_test, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        textureView = view.findViewById(R.id.texture_view)
        calibrator = view.findViewById(R.id.camera_calibrator)
        // 禁用设置界面根布局的显示矫正，避免相机预览叠加二次变形
        warpLayout = requireActivity().findViewById(R.id.keystone_root)
        warpLayout?.setWarpEnabled(false)
        // 相机校正：不绘制黑色背景、不填充四边形，仅显示边框与角点
        calibrator.setDrawBackground(false)
        calibrator.setFillPolygon(false)
        calibrator.isFocusable = true
        calibrator.isFocusableInTouchMode = true
        calibrator.requestFocus()

        view.findViewById<TextView>(R.id.hint_text)?.text = "视频输入矫正：拖动四角至可视边界，按返回保存/退出"

        calibrator.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = 0.01f
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { calibrator.nudgeSelected(0f, -step); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { calibrator.nudgeSelected(0f, +step); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { calibrator.nudgeSelected(-step, 0f); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { calibrator.nudgeSelected(+step, 0f); true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { calibrator.selectNext(); true }
                else -> false
            }
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            startPreviewWhenReady()
        }

        // 处理返回：提示保存相机输入区域
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            AlertDialog.Builder(requireContext())
                .setTitle("保存输入区域")
                .setMessage("是否保存当前的相机最大输入区域？")
                .setPositiveButton("保存") { _, _ ->
                    saveInputRegion()
                }
                .setNegativeButton("取消") { _, _ ->
                    parentFragmentManager.popBackStack()
                }
                .setCancelable(true)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 保守确保进入时禁用显示矫正
        warpLayout = requireActivity().findViewById(R.id.keystone_root)
        warpLayout?.setWarpEnabled(false)
    }

    private fun startPreviewWhenReady() {
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
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
        if (cameraId == null) {
            Toast.makeText(requireContext(), "未找到可用摄像头", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            
            // 获取相机特性和支持的分辨率
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            // 选择最佳分辨率用于图像处理
            val sizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
            val chosenSize = chooseOptimalSize(sizes)
            
            // 保存选择的分辨率
            previewWidth = chosenSize.width
            previewHeight = chosenSize.height
            
            android.util.Log.d("VideoInputTest", "Chosen camera resolution: ${chosenSize.width}x${chosenSize.height}")

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createPreviewSession()
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close(); cameraDevice = null
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null
                    Toast.makeText(requireContext(), "摄像头打开失败：$error", Toast.LENGTH_SHORT).show()
                }
            }, null)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "打开摄像头异常：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val surfaceTexture = textureView.surfaceTexture ?: return
        // 使用与beakerlab相同的固定尺寸640x480用于TextureView显示
        // 但实际相机分辨率使用动态选择的previewWidth和previewHeight
        surfaceTexture.setDefaultBufferSize(640, 480)
        val previewSurface = Surface(surfaceTexture)

        try {
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                
                // Set auto focus
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                
                // 自动曝光设置 - 增加曝光补偿
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                
                // 曝光补偿设置 - 增加曝光，提高亮度
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 4)
                
                // ISO设置 - 提高ISO增加感光度
                set(CaptureRequest.SENSOR_SENSITIVITY, 600)
                
                // 白平衡设置 - 使用自动白平衡
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AWB_LOCK, false)
                
                // 场景模式设置为自动
                set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }

            device.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        val request = previewRequestBuilder?.build()
                        if (request != null) {
                            session.setRepeatingRequest(request, null, null)
                        }
                    } catch (_: Exception) {}
                    // 应用矩阵变换，保证预览不拉伸且按需旋转
                    runCatching { configureTransform(textureView.width, textureView.height) }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(requireContext(), "预览会话创建失败", Toast.LENGTH_SHORT).show()
                }
            }, null)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "创建预览异常：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseCamera(manager: CameraManager): String? {
        return try {
            // 优先选择后置摄像头
            manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
        } catch (_: Exception) { null }
    }

    private fun choosePreviewSize(device: CameraDevice): Pair<Int, Int>? {
        val manager = requireContext().getSystemService(CameraManager::class.java)
        val characteristics = try { manager.getCameraCharacteristics(device.id) } catch (_: Exception) { return null }
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return null
        // 优先 640x480，其次选择最小且不大于 1280x720 的尺寸
        val preferred = sizes.firstOrNull { it.width == 640 && it.height == 480 }
        if (preferred != null) return Pair(preferred.width, preferred.height)
        var chosen: android.util.Size? = null
        for (s in sizes) {
            if (s.width <= 1280 && s.height <= 720) {
                if (chosen == null || s.width * s.height < (chosen!!.width * chosen!!.height)) {
                    chosen = s
                }
            }
        }
        val ch = chosen ?: sizes.minByOrNull { it.width * it.height } ?: return null
        return Pair(ch.width, ch.height)
    }
    
    private fun chooseOptimalSize(choices: Array<android.util.Size>?): android.util.Size {
        if (choices == null) return android.util.Size(1280, 960)
        
        // 优先选择4:3比例的分辨率，以匹配4:3横屏显示
        val preferred43Sizes = listOf(
            android.util.Size(1600, 1200),  // UXGA 4:3
            android.util.Size(1280, 960),   // SXGA 4:3
            android.util.Size(1024, 768),   // XGA 4:3
            android.util.Size(800, 600),    // SVGA 4:3
            android.util.Size(640, 480)     // VGA 4:3 (fallback)
        )
        
        // 备选16:9分辨率（如果没有4:3可用）
        val fallback169Sizes = listOf(
            android.util.Size(1920, 1080),  // Full HD 16:9
            android.util.Size(1280, 720),   // HD 16:9
            android.util.Size(960, 540)     // qHD 16:9
        )
        
        // 首先查找4:3比例的分辨率
        for (preferred in preferred43Sizes) {
            val match = choices.find { 
                it.width == preferred.width && it.height == preferred.height 
            }
            if (match != null) {
                android.util.Log.d("VideoInputTest", "Selected 4:3 camera resolution: ${match.width}x${match.height}")
                return match
            }
        }
        
        // 如果没有找到4:3分辨率，使用16:9作为备选
        for (preferred in fallback169Sizes) {
            val match = choices.find { 
                it.width == preferred.width && it.height == preferred.height 
            }
            if (match != null) {
                android.util.Log.d("VideoInputTest", "Selected 16:9 camera resolution (fallback): ${match.width}x${match.height}")
                return match
            }
        }
        
        // 最后的备选方案：选择最接近4:3比例的分辨率
        val target43Ratio = 4.0f / 3.0f
        val best43Match = choices.minByOrNull { size ->
            val ratio = size.width.toFloat() / size.height.toFloat()
            kotlin.math.abs(ratio - target43Ratio)
        }
        
        if (best43Match != null) {
            android.util.Log.d("VideoInputTest", "Selected closest to 4:3 ratio: ${best43Match.width}x${best43Match.height}")
            return best43Match
        }
        
        return choices.firstOrNull() ?: android.util.Size(1280, 960)
    }



    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        
        val matrix = android.graphics.Matrix()
        val rotation = -90f // 设置为-90度来抵消90度顺时针旋转
        
        // 使用实际的相机预览尺寸，而不是TextureView的缓冲区尺寸
        val cameraWidth = previewWidth.toFloat()
        val cameraHeight = previewHeight.toFloat()
        
        // 如果相机尺寸还没有设置，使用默认值
        if (cameraWidth == 0f || cameraHeight == 0f) {
            android.util.Log.w("VideoInputTest", "Camera size not set, using default 640x480")
            return
        }
        
        // 计算中心点
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        
        // 先旋转，再缩放
        matrix.postRotate(rotation, centerX, centerY)
        
        // -90度旋转后，原来的宽度变成高度，高度变成宽度
        // 所以我们需要将view的宽度与camera的高度比较，view的高度与camera的宽度比较
        val scaleX = viewWidth.toFloat() / cameraHeight   // viewWidth / cameraHeight (旋转后camera高度对应view宽度)
        val scaleY = viewHeight.toFloat() / cameraWidth   // viewHeight / cameraWidth (旋转后camera宽度对应view高度)
        
        // 使用较大的缩放比例来填满整个视图（CENTER_CROP效果），避免白边
        val scale = kotlin.math.max(scaleX, scaleY)
        
        // 应用缩放
        matrix.postScale(scale, scale, centerX, centerY)
        
        android.util.Log.d("VideoInputTest", "configureTransform: rotation=$rotation, scale=$scale, " +
                "cameraSize=${cameraWidth}x${cameraHeight}, " +
                "actualCameraSize=${previewWidth}x${previewHeight}, " +
                "viewSize=${viewWidth}x${viewHeight}, scaleX=$scaleX, scaleY=$scaleY")
        
        textureView.setTransform(matrix)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startPreviewWhenReady()
            } else {
                Toast.makeText(requireContext(), "需要摄像头权限以进行测试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开页面时恢复显示矫正
        warpLayout?.setWarpEnabled(true)
        closeCamera()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 销毁视图后进一步确保恢复显示矫正
        warpLayout?.setWarpEnabled(true)
        warpLayout = null
        closeCamera()
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
    }

    private fun saveInputRegion() {
        val csv = calibrator.buildCsv()
        // 使用与显示矫正一致的 TableOS Provider，作为长期存储参数
        val providerUri = Uri.parse("content://com.tableos.app.keystone/input_region")
        val ok = try {
            val values = ContentValues().apply { put("value", csv) }
            requireContext().contentResolver.insert(providerUri, values) != null
        } catch (e: Exception) { false }

        if (ok) {
            Toast.makeText(requireContext(), "已保存相机输入区域", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "保存失败：无法写入相机输入区域", Toast.LENGTH_SHORT).show()
        }
        parentFragmentManager.popBackStack()
    }
}