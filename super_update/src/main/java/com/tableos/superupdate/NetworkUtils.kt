package com.tableos.superupdate

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Get the local IP address of the device
     */
    fun getLocalIpAddress(context: Context): String? {
        try {
            // First try to get WiFi IP address
            val wifiIp = getWifiIpAddress(context)
            if (wifiIp != null && wifiIp != "0.0.0.0") {
                return wifiIp
            }

            // If WiFi is not available, try to get IP from network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                
                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    
                    // We want IPv4 addresses that are not loopback
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        if (ip != null && isValidPrivateIp(ip)) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: SocketException) {
            Log.e(TAG, "Error getting IP address", e)
        }
        
        return null
    }

    /**
     * Get WiFi IP address
     */
    private fun getWifiIpAddress(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WiFi IP", e)
        }
        
        return null
    }

    /**
     * Check if the IP address is a valid private IP
     */
    private fun isValidPrivateIp(ip: String): Boolean {
        return when {
            ip.startsWith("192.168.") -> true
            ip.startsWith("10.") -> true
            ip.startsWith("172.") -> {
                val parts = ip.split(".")
                if (parts.size >= 2) {
                    val secondOctet = parts[1].toIntOrNull() ?: 0
                    secondOctet in 16..31
                } else false
            }
            else -> false
        }
    }

    /**
     * Check if the device is connected to a network
     */
    fun isNetworkAvailable(context: Context): Boolean {
        Log.d(TAG, "🔍 检查网络连接状态...")
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val network = connectivityManager.activeNetwork
        if (network == null) {
            Log.w(TAG, "❌ 没有活动的网络连接")
            return false
        }
        Log.d(TAG, "✅ 找到活动网络: $network")
        
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
        if (networkCapabilities == null) {
            Log.w(TAG, "❌ 无法获取网络能力信息")
            return false
        }
        
        val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val hasValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val hasNotMetered = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        
        Log.d(TAG, "📊 网络能力检查:")
        Log.d(TAG, "   - 互联网连接: $hasInternet")
        Log.d(TAG, "   - 网络已验证: $hasValidated")
        Log.d(TAG, "   - 非计费网络: $hasNotMetered")
        Log.d(TAG, "   - 传输类型: ${getTransportTypes(networkCapabilities)}")
        
        if (hasInternet) {
            Log.i(TAG, "✅ 网络连接可用且具备互联网访问能力")
        } else {
            Log.w(TAG, "⚠️ 网络连接可用但无互联网访问能力")
        }
        
        return hasInternet
    }

    /**
     * Check if the device is connected to WiFi
     */
    fun isWifiConnected(context: Context): Boolean {
        Log.d(TAG, "🔍 检查WiFi连接状态...")
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val network = connectivityManager.activeNetwork
        if (network == null) {
            Log.w(TAG, "❌ 没有活动的网络连接")
            return false
        }
        
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
        if (networkCapabilities == null) {
            Log.w(TAG, "❌ 无法获取网络能力信息")
            return false
        }
        
        val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        Log.d(TAG, if (isWifi) "✅ 当前使用WiFi连接" else "ℹ️ 当前未使用WiFi连接")
        
        return isWifi
    }
    
    /**
     * Get transport types as string
     */
    private fun getTransportTypes(networkCapabilities: NetworkCapabilities): String {
        val transports = mutableListOf<String>()
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transports.add("WiFi")
        }
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transports.add("移动网络")
        }
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transports.add("以太网")
        }
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            transports.add("蓝牙")
        }
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            transports.add("VPN")
        }
        return if (transports.isEmpty()) "未知" else transports.joinToString(", ")
    }

    /**
     * Get network type as string
     */
    fun getNetworkType(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val network = connectivityManager.activeNetwork ?: return "无网络"
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return "未知"
        
        return when {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他"
        }
    }

    /**
     * Get public IP address using external service
     */
    suspend fun getPublicIpAddress(): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🌐 开始获取公网IP地址（仅使用HTTPS安全连接）")
        Log.d(TAG, "📋 网络安全配置: 禁用HTTP明文传输，仅允许HTTPS连接")
        
        // 只使用HTTPS服务，确保安全传输
        val httpsServices = listOf(
            "https://checkip.amazonaws.com",  // 测试确认可用
            "https://httpbin.org/ip",         // 测试确认可用
            "https://ipinfo.io/ip",           // 备用服务
            "https://icanhazip.com",          // 备用服务
            "https://api.ipify.org"           // 可能被阻止，放在最后
        )
        
        Log.d(TAG, "🔗 将尝试 ${httpsServices.size} 个HTTPS服务")
        
        var lastError: String? = null
        for ((index, service) in httpsServices.withIndex()) {
            Log.d(TAG, "🔄 [${index + 1}/${httpsServices.size}] 尝试服务: $service")
            val ip = tryGetIpFromService(service)
            if (ip != null) {
                Log.i(TAG, "✅ 通过HTTPS安全连接成功获取公网IP: $ip (来源: $service)")
                return@withContext ip
            } else {
                lastError = "服务 $service 失败"
                Log.w(TAG, "❌ [$index + 1}/${httpsServices.size}] 服务失败: $service")
            }
        }
        
        Log.e(TAG, "❌ 所有HTTPS公网IP获取服务都失败")
        Log.e(TAG, "🔍 调试信息: 最后一个错误 - $lastError")
        Log.e(TAG, "💡 建议检查: 1) 网络连接 2) 防火墙设置 3) 网络安全配置")
        return@withContext null
    }
    
    private suspend fun tryGetIpFromService(service: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔗 尝试从 $service 获取公网IP")
            
            val url = URL(service)
            val connection = url.openConnection() as java.net.HttpURLConnection
            
            // 基本连接设置
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000 // 增加到15秒
            connection.readTimeout = 15000 // 增加到15秒
            connection.setRequestProperty("User-Agent", "SuperUpdate/1.0 (Android)")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Connection", "close")
            
            Log.d(TAG, "⚙️ 连接配置: 超时=${connection.connectTimeout}ms, 读取超时=${connection.readTimeout}ms")
            
            // HTTPS特殊处理
            if (connection is javax.net.ssl.HttpsURLConnection) {
                Log.d(TAG, "🔒 配置HTTPS连接: $service")
                // 使用默认的SSL上下文和主机名验证器
                connection.sslSocketFactory = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
                connection.hostnameVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                Log.d(TAG, "🔐 SSL配置完成，使用系统默认证书")
            }
            
            // 尝试连接
            Log.d(TAG, "🚀 开始连接到 $service")
            val startTime = System.currentTimeMillis()
            connection.connect()
            val connectTime = System.currentTimeMillis() - startTime
            
            val responseCode = connection.responseCode
            Log.d(TAG, "📡 从 $service 收到响应码: $responseCode (连接耗时: ${connectTime}ms)")
            
            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val readStartTime = System.currentTimeMillis()
                val response = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                val readTime = System.currentTimeMillis() - readStartTime
                Log.d(TAG, "📥 从 $service 获取到响应: $response (读取耗时: ${readTime}ms)")
                
                // 处理httpbin.org的JSON响应
                val ip = if (service.contains("httpbin.org") && response.startsWith("{")) {
                    try {
                        // 简单的JSON解析，提取origin字段
                        val originStart = response.indexOf("\"origin\": \"") + 11
                        val originEnd = response.indexOf("\"", originStart)
                        if (originStart > 10 && originEnd > originStart) {
                            response.substring(originStart, originEnd).split(",")[0].trim()
                        } else {
                            response
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 解析httpbin.org响应失败: $e")
                        response
                    }
                } else {
                    response
                }
                
                // 验证IP格式
                if (isValidIpAddress(ip)) {
                    Log.i(TAG, "✅ 成功获取公网IP: $ip (来源: $service, 总耗时: ${System.currentTimeMillis() - startTime}ms)")
                    return@withContext ip
                } else {
                    Log.w(TAG, "❌ 从 $service 获取到无效IP: $ip")
                }
            } else {
                Log.w(TAG, "❌ 从 $service 获取IP失败，HTTP状态码: $responseCode")
                // 尝试读取错误响应
                try {
                    val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    if (!errorResponse.isNullOrBlank()) {
                        Log.w(TAG, "🔍 错误响应内容: $errorResponse")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "无法读取错误响应: ${e.message}")
                }
            }
            
            connection.disconnect()
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "🚫 连接被拒绝: $service - ${e.message}")
            Log.w(TAG, "💡 可能原因: 1) 服务器拒绝连接 2) 防火墙阻止 3) 网络不可达")
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "⏰ 连接超时: $service - ${e.message}")
            Log.w(TAG, "💡 可能原因: 1) 网络延迟过高 2) 服务器响应慢 3) 网络不稳定")
        } catch (e: javax.net.ssl.SSLException) {
            Log.w(TAG, "🔒 SSL连接失败: $service - ${e.message}")
            Log.w(TAG, "💡 可能原因: 1) SSL证书问题 2) TLS版本不兼容 3) 网络安全配置限制")
        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "🌐 域名解析失败: $service - ${e.message}")
            Log.w(TAG, "💡 可能原因: 1) DNS解析失败 2) 域名不存在 3) 网络DNS配置问题")
        } catch (e: java.security.cert.CertificateException) {
            Log.w(TAG, "📜 证书验证失败: $service - ${e.message}")
            Log.w(TAG, "💡 可能原因: 1) 证书过期 2) 证书不受信任 3) 证书链问题")
        } catch (e: Exception) {
            Log.w(TAG, "❌ 从 $service 获取IP时发生异常: ${e.javaClass.simpleName} - ${e.message}")
            Log.w(TAG, "🔍 异常堆栈: ${e.stackTrace.take(3).joinToString { it.toString() }}")
        }
        
        return@withContext null
    }

    /**
     * Validate if a string is a valid IP address
     */
    private fun isValidIpAddress(ip: String): Boolean {
        return try {
            val parts = ip.split(".")
            if (parts.size != 4) return false
            
            parts.all { part ->
                val num = part.toIntOrNull()
                num != null && num in 0..255
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Find an available port starting from the given port
     */
    fun findAvailablePort(startPort: Int = 8080): Int {
        for (port in startPort..65535) {
            try {
                java.net.ServerSocket(port).use {
                    return port
                }
            } catch (e: Exception) {
                // Port is in use, try next one
            }
        }
        return startPort // Fallback to original port
    }
    
    /**
     * Check network permissions
     */
    fun checkNetworkPermissions(context: Context): Map<String, Boolean> {
        Log.d(TAG, "🔐 检查网络权限...")
        
        val permissions = mapOf(
            "INTERNET" to android.Manifest.permission.INTERNET,
            "ACCESS_NETWORK_STATE" to android.Manifest.permission.ACCESS_NETWORK_STATE,
            "ACCESS_WIFI_STATE" to android.Manifest.permission.ACCESS_WIFI_STATE
        )
        
        val results = mutableMapOf<String, Boolean>()
        
        permissions.forEach { (name, permission) ->
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            results[name] = granted
            Log.d(TAG, "   - $name: ${if (granted) "✅ 已授权" else "❌ 未授权"}")
        }
        
        val allGranted = results.values.all { it }
        Log.i(TAG, "🔐 网络权限检查结果: ${if (allGranted) "✅ 全部授权" else "⚠️ 部分权限缺失"}")
        
        return results
    }
    
    /**
     * Comprehensive network diagnostics
     */
    fun performNetworkDiagnostics(context: Context): String {
        Log.i(TAG, "🔍 开始网络诊断...")
        
        val diagnostics = StringBuilder()
        diagnostics.appendLine("📊 网络诊断报告")
        diagnostics.appendLine("=".repeat(40))
        
        // 1. 权限检查
        diagnostics.appendLine("\n🔐 权限检查:")
        val permissions = checkNetworkPermissions(context)
        permissions.forEach { (name, granted) ->
            diagnostics.appendLine("   - $name: ${if (granted) "✅" else "❌"}")
        }
        
        // 2. 网络状态
        diagnostics.appendLine("\n📱 网络状态:")
        val isNetworkAvailable = isNetworkAvailable(context)
        val isWifiConnected = isWifiConnected(context)
        val networkType = getNetworkType(context)
        
        diagnostics.appendLine("   - 网络可用: ${if (isNetworkAvailable) "✅" else "❌"}")
        diagnostics.appendLine("   - WiFi连接: ${if (isWifiConnected) "✅" else "❌"}")
        diagnostics.appendLine("   - 网络类型: $networkType")
        
        // 3. IP地址信息
        diagnostics.appendLine("\n🏠 IP地址信息:")
        val localIp = getLocalIpAddress(context)
        diagnostics.appendLine("   - 本地IP: ${localIp ?: "未获取到"}")
        
        // 4. 网络能力详情
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
            
            if (networkCapabilities != null) {
                diagnostics.appendLine("\n🔧 网络能力:")
                diagnostics.appendLine("   - 互联网访问: ${if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "✅" else "❌"}")
                diagnostics.appendLine("   - 网络已验证: ${if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "✅" else "❌"}")
                diagnostics.appendLine("   - 非计费网络: ${if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "✅" else "❌"}")
                diagnostics.appendLine("   - 传输类型: ${getTransportTypes(networkCapabilities)}")
            }
        } catch (e: Exception) {
            diagnostics.appendLine("\n❌ 网络能力检查失败: ${e.message}")
        }
        
        val result = diagnostics.toString()
        Log.i(TAG, "🔍 网络诊断完成:\n$result")
        return result
    }
}