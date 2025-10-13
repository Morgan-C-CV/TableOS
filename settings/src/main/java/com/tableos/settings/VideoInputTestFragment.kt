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
        surfaceTexture.setDefaultBufferSize(textureView.width, textureView.height)
        val previewSurface = Surface(surfaceTexture)

        try {
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
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