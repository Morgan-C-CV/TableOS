package com.tableos.superupdate

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionUtils {
    
    private const val TAG = "PermissionUtils"
    
    /**
     * 检查是否有安装权限
     */
    fun hasInstallPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hasPermission = context.packageManager.canRequestPackageInstalls()
            Log.d(TAG, "Install permission status (API 26+): $hasPermission")
            hasPermission
        } else {
            // Android 8.0以下默认有安装权限
            Log.d(TAG, "Install permission status (API < 26): true (default)")
            true
        }
    }
    
    /**
     * 请求安装权限
     */
    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Log.d(TAG, "Requesting install permission")
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            } else {
                Log.d(TAG, "Install permission already granted")
            }
        }
    }
    
    /**
     * 检查存储权限
     */
    fun hasStoragePermission(context: Context): Boolean {
        val readPermission = ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        
        val writePermission = ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        
        Log.d(TAG, "Storage permissions - Read: $readPermission, Write: $writePermission")
        return readPermission && writePermission
    }
    
    /**
     * 请求存储权限
     */
    fun requestStoragePermission(activity: Activity, requestCode: Int) {
        Log.d(TAG, "Requesting storage permissions")
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            requestCode
        )
    }
    
    /**
     * 检查所有必要权限
     */
    fun checkAllPermissions(context: Context): PermissionStatus {
        val installPermission = hasInstallPermission(context)
        val storagePermission = hasStoragePermission(context)
        val rootPermission = RootUtils.hasRootPermission()
        val deviceRooted = RootUtils.isDeviceRooted()
        val deviceAdminPermission = hasDeviceAdminPermission(context)
        
        Log.d(TAG, "Permission status - Install: $installPermission, Storage: $storagePermission, Root: $rootPermission, Device Rooted: $deviceRooted, Device Admin: $deviceAdminPermission")
        
        return PermissionStatus(
            hasInstallPermission = installPermission,
            hasStoragePermission = storagePermission,
            hasRootPermission = rootPermission,
            isDeviceRooted = deviceRooted,
            hasDeviceAdminPermission = deviceAdminPermission
        )
    }
    
    /**
     * 尝试获取系统级权限
     */
    fun tryGrantSystemPermissions(context: Context): Boolean {
        return try {
            Log.d(TAG, "Attempting to grant system permissions")
            
            if (RootUtils.hasRootPermission()) {
                val result = RootUtils.grantSystemPermissions(context.packageName)
                Log.d(TAG, "System permissions grant result: $result")
                result
            } else {
                Log.w(TAG, "No root permission available for system permissions")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error granting system permissions", e)
            false
        }
    }
    
    /**
     * 检查是否为系统应用
     */
    fun isSystemApp(context: Context): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            (packageInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking system app status", e)
            false
        }
    }
    
    /**
     * 检查是否有设备管理员权限
     */
    fun hasDeviceAdminPermission(context: Context): Boolean {
        return try {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
            devicePolicyManager.isAdminActive(componentName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device admin permission", e)
            false
        }
    }
    
    /**
     * 请求设备管理员权限
     */
    fun requestDeviceAdminPermission(activity: Activity, requestCode: Int) {
        try {
            val componentName = ComponentName(activity, DeviceAdminReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "需要设备管理员权限来进行系统级操作")
            }
            activity.startActivityForResult(intent, requestCode)
            Log.d(TAG, "Requesting device admin permission")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting device admin permission", e)
        }
    }
    
    /**
     * 获取权限详细信息
     */
    fun getPermissionDetails(context: Context): String {
        val status = checkAllPermissions(context)
        val isSystemApp = isSystemApp(context)
        
        return buildString {
            appendLine("=== 权限状态详情 ===")
            appendLine("安装权限: ${if (status.hasInstallPermission) "✅ 已授予" else "❌ 未授予"}")
            appendLine("存储权限: ${if (status.hasStoragePermission) "✅ 已授予" else "❌ 未授予"}")
            appendLine("Root权限: ${if (status.hasRootPermission) "✅ 已获得" else "❌ 未获得"}")
            appendLine("设备Root状态: ${if (status.isDeviceRooted) "✅ 已Root" else "❌ 未Root"}")
            appendLine("设备管理员: ${if (status.hasDeviceAdminPermission) "✅ 已激活" else "❌ 未激活"}")
            appendLine("系统应用: ${if (isSystemApp) "✅ 是" else "❌ 否"}")
            appendLine("Android版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
    }
}

/**
 * 权限状态数据类
 */
data class PermissionStatus(
    val hasInstallPermission: Boolean,
    val hasStoragePermission: Boolean,
    val hasRootPermission: Boolean,
    val isDeviceRooted: Boolean,
    val hasDeviceAdminPermission: Boolean
)