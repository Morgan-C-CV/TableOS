package com.tableos.settings

import android.app.AlertDialog
import android.content.Intent
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
        val canWrite = Settings.System.canWrite(requireContext())
        if (!canWrite) {
            Toast.makeText(requireContext(), "请允许修改系统设置以保存矫正", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                startActivity(intent)
            } catch (_: Exception) {
                // 忽略
            }
            return
        }
        try {
            Settings.System.putString(requireContext().contentResolver, "tableos_keystone", csv)
            Toast.makeText(requireContext(), "已保存桌面矫正设置", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
        parentFragmentManager.popBackStack()
    }
}