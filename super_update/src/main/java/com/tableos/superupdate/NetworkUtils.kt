package com.tableos.superupdate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

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
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Check if the device is connected to WiFi
     */
    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
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
        throw RuntimeException("No available port found")
    }
}