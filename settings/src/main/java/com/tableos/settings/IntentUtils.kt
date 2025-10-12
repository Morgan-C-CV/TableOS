package com.tableos.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object IntentUtils {
    /**
     * 尝试打开“修改系统设置”授权页面，包含多级回退：
     * 1) 直接打开 WRITE_SETTINGS 页面并携带当前包名
     * 2) 不携带包名的 WRITE_SETTINGS 页面
     * 3) 打开本应用详情页
     * 4) 打开系统设置首页
     * 返回是否成功启动任一页面。
     */
    fun openManageWriteSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in intents) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // 继续尝试下一个回退入口
            } catch (_: Exception) {
                // 继续尝试下一个回退入口
            }
        }
        return false
    }
}