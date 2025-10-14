package com.tableos.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
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
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { cameraDevice = device; createPreviewSession() }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
                override fun onError(device: CameraDevice, error: Int) { device.close(); cameraDevice = null; Toast.makeText(requireContext(), "摄像头打开失败：$error", Toast.LENGTH_SHORT).show() }
            }, null)
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
        val width = textureView.width.coerceAtLeast(640)
        val height = textureView.height.coerceAtLeast(480)
        surfaceTexture.setDefaultBufferSize(width, height)
        val previewSurface = Surface(surfaceTexture)

        // ImageReader for YUV_420_888 frames
        imageReader = ImageReader.newInstance(width, height, android.graphics.ImageFormat.YUV_420_888, 4)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!processing) {
                processing = true
                try { processImage(image) } catch (_: Exception) {} finally { processing = false }
            }
            image.close()
        }, null)

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
                    try { previewRequestBuilder?.build()?.let { session.setRepeatingRequest(it, null, null) } } catch (_: Exception) {}
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(requireContext(), "预览会话创建失败", Toast.LENGTH_SHORT).show()
                }
            }, null)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "创建预览异常：${e.message}", Toast.LENGTH_SHORT).show()
        }
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
        val sx = textureView.width.toFloat() / width
        val sy = textureView.height.toFloat() / height
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
            boxes.add(RectF(tlx * sx, tly * sy, brx * sx, bry * sy))
        }

        requireActivity().runOnUiThread {
            resultText.text = sb.toString()
            overlay.showBoxes(boxes)
        }
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        closeCamera()
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        imageReader?.close(); imageReader = null
    }
}