package com.tableos.superupdate

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

object RootUtils {
    
    private const val TAG = "RootUtils"
    
    /**
     * 检查设备是否已root
     */
    fun isDeviceRooted(): Boolean {
        return checkRootMethod1() || checkRootMethod2() || checkRootMethod3()
    }
    
    /**
     * 检查是否有root权限
     */
    fun hasRootPermission(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            outputStream.writeBytes("id\n")
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            
            val exitValue = process.waitFor()
            Log.d(TAG, "Root permission check exit value: $exitValue")
            exitValue == 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking root permission", e)
            false
        }
    }
    
    /**
     * 执行root命令
     */
    fun executeRootCommand(command: String): RootCommandResult {
        return try {
            Log.d(TAG, "Executing root command: $command")
            
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            
            outputStream.writeBytes("$command\n")
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val output = StringBuilder()
            val error = StringBuilder()
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            
            while (errorReader.readLine().also { line = it } != null) {
                error.append(line).append("\n")
            }
            
            val exitValue = process.waitFor()
            
            reader.close()
            errorReader.close()
            outputStream.close()
            
            Log.d(TAG, "Command executed with exit code: $exitValue")
            Log.d(TAG, "Output: ${output.toString().trim()}")
            if (error.isNotEmpty()) {
                Log.w(TAG, "Error: ${error.toString().trim()}")
            }
            
            RootCommandResult(
                success = exitValue == 0,
                output = output.toString().trim(),
                error = error.toString().trim(),
                exitCode = exitValue
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error executing root command: $command", e)
            RootCommandResult(
                success = false,
                output = "",
                error = e.message ?: "Unknown error",
                exitCode = -1
            )
        }
    }
    
    /**
     * 使用root权限安装APK
     */
    fun installApkWithRoot(apkPath: String): Boolean {
        return try {
            Log.d(TAG, "Installing APK with root: $apkPath")
            
            // 使用pm install命令安装APK
            val result = executeRootCommand("pm install -r \"$apkPath\"")
            
            if (result.success) {
                Log.d(TAG, "APK installed successfully with root")
                true
            } else {
                Log.e(TAG, "Failed to install APK with root: ${result.error}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK with root", e)
            false
        }
    }
    
    /**
     * 获取系统权限，设置应用为系统应用
     */
    fun grantSystemPermissions(packageName: String): Boolean {
        return try {
            Log.d(TAG, "Granting system permissions to: $packageName")
            
            // 授予安装权限
            val result1 = executeRootCommand("pm grant $packageName android.permission.INSTALL_PACKAGES")
            val result2 = executeRootCommand("pm grant $packageName android.permission.DELETE_PACKAGES")
            val result3 = executeRootCommand("pm grant $packageName android.permission.REQUEST_INSTALL_PACKAGES")
            
            // 设置为设备管理员
            val result4 = executeRootCommand("dpm set-device-owner $packageName/.DeviceAdminReceiver")
            
            Log.d(TAG, "Permission grant results: ${result1.success}, ${result2.success}, ${result3.success}, ${result4.success}")
            
            result1.success || result2.success || result3.success
        } catch (e: Exception) {
            Log.e(TAG, "Error granting system permissions", e)
            false
        }
    }
    
    // 检查方法1：检查常见的root文件
    private fun checkRootMethod1(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        
        for (path in paths) {
            if (File(path).exists()) {
                Log.d(TAG, "Root file found: $path")
                return true
            }
        }
        return false
    }
    
    // 检查方法2：检查系统属性
    private fun checkRootMethod2(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine() != null
            reader.close()
            result
        } catch (e: Exception) {
            false
        }
    }
    
    // 检查方法3：尝试执行su命令
    private fun checkRootMethod3(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val exitValue = process.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Root命令执行结果
 */
data class RootCommandResult(
    val success: Boolean,
    val output: String,
    val error: String,
    val exitCode: Int
)