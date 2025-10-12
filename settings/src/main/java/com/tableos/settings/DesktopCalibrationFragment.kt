package com.tableos.settings

import android.app.AlertDialog
import android.content.Intent
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.fragment.app.Fragment

class DesktopCalibrationFragment : Fragment() {
    private lateinit var calibrator: KeystoneCalibratorView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_desktop_calibration, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        calibrator = view.findViewById(R.id.keystone_calibrator)
        calibrator.isFocusable = true
        calibrator.isFocusableInTouchMode = true
        calibrator.requestFocus()

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

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            AlertDialog.Builder(requireContext())
                .setTitle("保存设置")
                .setMessage("是否保存当前的桌面矫正配置？")
                .setPositiveButton("保存") { _, _ ->
                    saveConfig()
                }
                .setNegativeButton("取消") { _, _ ->
                    // 直接返回上一页
                    parentFragmentManager.popBackStack()
                }
                .setCancelable(true)
                .show()
        }
    }

    private fun saveConfig() {
        val csv = calibrator.buildCsv()
        // 优先写入 TableOS 私有 ContentProvider，避免修改全局系统设置
        val providerUri = Uri.parse("content://com.tableos.app.keystone/config")
        val ok = try {
            val values = ContentValues().apply { put("value", csv) }
            requireContext().contentResolver.insert(providerUri, values) != null
        } catch (e: Exception) { false }

        if (ok) {
            Toast.makeText(requireContext(), "已保存桌面矫正设置（仅 TableOS 生效）", Toast.LENGTH_SHORT).show()
            // 保存成功后自动回到 TableOS 并应用矫正
            val pm = requireContext().packageManager
            val launch = pm.getLaunchIntentForPackage("com.tableos.app")
            if (launch != null) {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                try {
                    startActivity(launch)
                    requireActivity().finish()
                } catch (_: Exception) {
                    // 启动失败时仅保留提示，不崩溃
                }
            }
        } else {
            Toast.makeText(requireContext(), "保存失败：无法写入 TableOS 配置提供者", Toast.LENGTH_SHORT).show()
        }
        parentFragmentManager.popBackStack()
    }
}