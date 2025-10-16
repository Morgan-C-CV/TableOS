package com.tableos.superupdate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress

class UpdateService : Service() {

    companion object {
        private const val TAG = "UpdateService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "super_update_channel"
        private const val DEFAULT_PORT = 8080
        
        const val ACTION_START_SERVICE = "com.tableos.superupdate.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.tableos.superupdate.STOP_SERVICE"
    }

    private val binder = UpdateServiceBinder()
    private var webSocketServer: UpdateWebSocketServer? = null
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    
    private var currentPort = DEFAULT_PORT
    private var isServiceRunning = false
    
    // Callbacks for UI updates
    private var onServiceStateChanged: ((Boolean) -> Unit)? = null
    private var onClientConnected: (() -> Unit)? = null
    private var onClientDisconnected: (() -> Unit)? = null
    private var onProgress: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    inner class UpdateServiceBinder : Binder() {
        fun getService(): UpdateService = this@UpdateService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "UpdateService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> startUpdateService()
            ACTION_STOP_SERVICE -> stopUpdateService()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "UpdateService destroyed")
        stopUpdateService()
    }

    fun startUpdateService() {
        if (isServiceRunning) {
            Log.d(TAG, "Service already running")
            return
        }

        serviceJob = serviceScope.launch {
            try {
                // Find available port
                currentPort = NetworkUtils.findAvailablePort(DEFAULT_PORT)
                
                // Create WebSocket server
                val address = InetSocketAddress(currentPort)
                webSocketServer = UpdateWebSocketServer(
                    address = address,
                    onClientConnected = {
                        onClientConnected?.invoke()
                    },
                    onClientDisconnected = {
                        onClientDisconnected?.invoke()
                    },
                    onApkReceived = { apkFile ->
                        installApk(apkFile)
                    },
                    onProgress = { message ->
                        onProgress?.invoke(message)
                    },
                    onError = { error ->
                        onError?.invoke(error)
                    }
                )

                // Start server
                webSocketServer?.start()
                
                // Set connection timeout to 10 minutes (600 seconds) to handle large file transfers
                // This enables automatic ping/pong to keep connection alive
                webSocketServer?.connectionLostTimeout = 600
                
                // Enable TCP no delay for better performance
                webSocketServer?.setTcpNoDelay(true)
                
                isServiceRunning = true
                
                // Start foreground service
                startForeground(NOTIFICATION_ID, createNotification())
                
                onServiceStateChanged?.invoke(true)
                onProgress?.invoke("服务已启动，端口: $currentPort")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
                onError?.invoke("启动服务失败: ${e.message}")
                isServiceRunning = false
                onServiceStateChanged?.invoke(false)
            }
        }
    }

    fun stopUpdateService() {
        if (!isServiceRunning) {
            Log.d(TAG, "Service not running")
            return
        }

        try {
            webSocketServer?.stop()
            webSocketServer = null
            serviceJob?.cancel()
            serviceJob = null
            isServiceRunning = false
            
            stopForeground(STOP_FOREGROUND_REMOVE)
            
            onServiceStateChanged?.invoke(false)
            onProgress?.invoke("服务已停止")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service", e)
            onError?.invoke("停止服务时出错: ${e.message}")
        }
    }

    private fun installApk(apkFile: File) {
        try {
            onProgress?.invoke("开始安装APK...")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Use FileProvider for Android 7.0+
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    apkFile
                )
                installApkWithUri(uri)
            } else {
                // Direct file URI for older versions
                val uri = Uri.fromFile(apkFile)
                installApkWithUri(uri)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
            onError?.invoke("安装APK失败: ${e.message}")
            // Clean up temp file
            apkFile.delete()
        }
    }

    private fun installApkWithUri(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Use PackageInstaller for Android 5.0+
            installApkWithPackageInstaller(uri)
        } else {
            // Use Intent for older versions
            installApkWithIntent(uri)
        }
    }

    private fun installApkWithPackageInstaller(uri: Uri) {
        try {
            val packageInstaller = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            
            // Copy APK to session
            contentResolver.openInputStream(uri)?.use { inputStream ->
                session.openWrite("package", 0, -1).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            // Create install intent
            val intent = Intent(this, InstallResultReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            session.commit(pendingIntent.intentSender)
            session.close()
            
            onProgress?.invoke("APK安装请求已提交")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error with PackageInstaller", e)
            // Fallback to intent method
            installApkWithIntent(uri)
        }
    }

    private fun installApkWithIntent(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        
        try {
            startActivity(intent)
            onProgress?.invoke("已打开安装界面")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting install intent", e)
            onError?.invoke("无法打开安装界面: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Super Update Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Super Update background service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // Public methods for UI binding
    fun isRunning(): Boolean = isServiceRunning
    fun getCurrentPort(): Int = currentPort
    fun getConnectedClientsCount(): Int = webSocketServer?.getConnectedClientsCount() ?: 0

    fun setOnServiceStateChanged(callback: (Boolean) -> Unit) {
        onServiceStateChanged = callback
    }

    fun setOnClientConnected(callback: () -> Unit) {
        onClientConnected = callback
    }

    fun setOnClientDisconnected(callback: () -> Unit) {
        onClientDisconnected = callback
    }

    fun setOnProgress(callback: (String) -> Unit) {
        onProgress = callback
    }

    fun setOnError(callback: (String) -> Unit) {
        onError = callback
    }
}