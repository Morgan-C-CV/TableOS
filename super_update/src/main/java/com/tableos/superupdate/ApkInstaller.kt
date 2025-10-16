package com.tableos.superupdate

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

object ApkInstaller {
    
    private const val TAG = "ApkInstaller"
    
    /**
     * 安装方式枚举
     */
    enum class InstallMethod {
        NORMAL,      // 普通安装（需要用户确认）
        ROOT,        // Root静默安装
        SYSTEM       // 系统级安装
    }
    
    /**
     * 安装结果
     */
    data class InstallResult(
        val success: Boolean,
        val method: InstallMethod,
        val message: String,
        val errorCode: Int = 0
    )
    
    /**
     * 智能安装APK - 自动选择最佳安装方式
     */
    fun installApk(context: Context, apkFile: File): InstallResult {
        Log.d(TAG, "Starting APK installation: ${apkFile.absolutePath}")
        
        if (!apkFile.exists()) {
            return InstallResult(false, InstallMethod.NORMAL, "APK文件不存在")
        }
        
        val permissionStatus = PermissionUtils.checkAllPermissions(context)
        
        // 优先尝试Root安装（静默）
        if (permissionStatus.hasRootPermission) {
            Log.d(TAG, "Attempting root installation")
            val rootResult = installWithRoot(apkFile)
            if (rootResult.success) {
                return rootResult
            }
            Log.w(TAG, "Root installation failed: ${rootResult.message}")
        }
        
        // 尝试系统级安装
        if (PermissionUtils.isSystemApp(context)) {
            Log.d(TAG, "Attempting system installation")
            val systemResult = installWithSystem(context, apkFile)
            if (systemResult.success) {
                return systemResult
            }
            Log.w(TAG, "System installation failed: ${systemResult.message}")
        }
        
        // 回退到普通安装
        Log.d(TAG, "Falling back to normal installation")
        return installNormal(context, apkFile)
    }
    
    /**
     * 普通安装方式
     */
    private fun installNormal(context: Context, apkFile: File): InstallResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile
                    )
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(intent)
            Log.d(TAG, "Normal installation intent started")
            InstallResult(true, InstallMethod.NORMAL, "已启动安装界面")
            
        } catch (e: Exception) {
            Log.e(TAG, "Normal installation failed", e)
            InstallResult(false, InstallMethod.NORMAL, "启动安装界面失败: ${e.message}")
        }
    }
    
    /**
     * Root静默安装
     */
    private fun installWithRoot(apkFile: File): InstallResult {
        return try {
            val success = RootUtils.installApkWithRoot(apkFile.absolutePath)
            if (success) {
                Log.d(TAG, "Root installation successful")
                InstallResult(true, InstallMethod.ROOT, "Root静默安装成功")
            } else {
                Log.e(TAG, "Root installation failed")
                InstallResult(false, InstallMethod.ROOT, "Root安装失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root installation error", e)
            InstallResult(false, InstallMethod.ROOT, "Root安装异常: ${e.message}")
        }
    }
    
    /**
     * 系统级安装（使用PackageInstaller）
     */
    private fun installWithSystem(context: Context, apkFile: File): InstallResult {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            
            val outputStream: OutputStream = session.openWrite("package", 0, apkFile.length())
            val inputStream = FileInputStream(apkFile)
            
            inputStream.copyTo(outputStream)
            session.fsync(outputStream)
            inputStream.close()
            outputStream.close()
            
            // 创建安装意图
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 
                0, 
                intent, 
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )
            
            session.commit(pendingIntent.intentSender)
            session.close()
            
            Log.d(TAG, "System installation session committed")
            InstallResult(true, InstallMethod.SYSTEM, "系统级安装已提交")
            
        } catch (e: Exception) {
            Log.e(TAG, "System installation failed", e)
            InstallResult(false, InstallMethod.SYSTEM, "系统级安装失败: ${e.message}")
        }
    }
    
    /**
     * 检查APK是否可以安装
     */
    fun canInstallApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) {
            Log.w(TAG, "APK file does not exist: ${apkFile.absolutePath}")
            return false
        }
        
        val permissionStatus = PermissionUtils.checkAllPermissions(context)
        
        return when {
            permissionStatus.hasRootPermission -> {
                Log.d(TAG, "Can install with root permission")
                true
            }
            PermissionUtils.isSystemApp(context) -> {
                Log.d(TAG, "Can install as system app")
                true
            }
            permissionStatus.hasInstallPermission -> {
                Log.d(TAG, "Can install with normal permission")
                true
            }
            else -> {
                Log.w(TAG, "No installation permission available")
                false
            }
        }
    }
    
    /**
     * 获取推荐的安装方式
     */
    fun getRecommendedInstallMethod(context: Context): InstallMethod {
        val permissionStatus = PermissionUtils.checkAllPermissions(context)
        
        return when {
            permissionStatus.hasRootPermission -> {
                Log.d(TAG, "Recommended method: ROOT")
                InstallMethod.ROOT
            }
            PermissionUtils.isSystemApp(context) -> {
                Log.d(TAG, "Recommended method: SYSTEM")
                InstallMethod.SYSTEM
            }
            else -> {
                Log.d(TAG, "Recommended method: NORMAL")
                InstallMethod.NORMAL
            }
        }
    }
    
    /**
     * 获取安装能力描述
     */
    fun getInstallCapabilities(context: Context): String {
        val permissionStatus = PermissionUtils.checkAllPermissions(context)
        val isSystemApp = PermissionUtils.isSystemApp(context)
        
        return buildString {
            appendLine("=== 安装能力 ===")
            
            if (permissionStatus.hasRootPermission) {
                appendLine("✅ Root静默安装 - 无需用户确认")
            } else if (permissionStatus.isDeviceRooted) {
                appendLine("⚠️ Root静默安装 - 需要授权Root权限")
            } else {
                appendLine("❌ Root静默安装 - 设备未Root")
            }
            
            if (isSystemApp) {
                appendLine("✅ 系统级安装 - 高权限安装")
            } else {
                appendLine("❌ 系统级安装 - 非系统应用")
            }
            
            if (permissionStatus.hasInstallPermission) {
                appendLine("✅ 普通安装 - 需要用户确认")
            } else {
                appendLine("⚠️ 普通安装 - 需要授权安装权限")
            }
            
            appendLine("\n推荐方式: ${getRecommendedInstallMethod(context).name}")
        }
    }
}