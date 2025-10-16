package com.tableos.superupdate

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tableos.superupdate.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_INSTALL_PACKAGES = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private var updateService: UpdateService? = null
    private var isServiceBound = false
    
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as UpdateService.UpdateServiceBinder
            updateService = binder.getService()
            isServiceBound = true
            setupServiceCallbacks()
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            updateService = null
            isServiceBound = false
        }
    }

    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.tableos.superupdate.INSTALL_SUCCESS" -> {
                    addLogMessage("✅ APK安装成功")
                    Toast.makeText(this@MainActivity, "APK安装成功", Toast.LENGTH_SHORT).show()
                }
                "com.tableos.superupdate.INSTALL_FAILURE" -> {
                    val error = intent.getStringExtra("error_message") ?: "未知错误"
                    addLogMessage("❌ APK安装失败: $error")
                    Toast.makeText(this@MainActivity, "APK安装失败: $error", Toast.LENGTH_LONG).show()
                }
                "com.tableos.superupdate.INSTALL_ABORTED" -> {
                    addLogMessage("⚠️ 用户取消了安装")
                }
                "com.tableos.superupdate.INSTALL_BLOCKED" -> {
                    val error = intent.getStringExtra("error_message") ?: "安装被阻止"
                    addLogMessage("🚫 安装被阻止: $error")
                }
            }
        }
    }

    private val requestInstallPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                addLogMessage("✅ 已获得安装权限")
            } else {
                addLogMessage("❌ 未获得安装权限")
                showInstallPermissionDialog()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()
        bindToService()
        registerInstallResultReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        unregisterReceiver(installResultReceiver)
    }

    private fun setupUI() {
        binding.btnToggleService.setOnClickListener {
            toggleService()
        }

        // Display IP address
        val ipAddress = NetworkUtils.getLocalIpAddress(this)
        if (ipAddress != null) {
            binding.tvIpAddress.text = ipAddress
            updateConnectionInfo(ipAddress, 8080) // Default port, will be updated when service starts
        } else {
            binding.tvIpAddress.text = "无法获取IP地址"
            addLogMessage("⚠️ 无法获取本机IP地址，请检查网络连接")
        }

        // Initial UI state
        updateServiceStatus(false)
        addLogMessage("📱 Super Update 已启动")
        addLogMessage("💡 点击'启动服务'开始监听远程连接")
    }

    private fun checkPermissions() {
        // Check install packages permission for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                showInstallPermissionDialog()
            }
        }

        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1002
                )
            }
        }
    }

    private fun showInstallPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要安装权限")
            .setMessage("为了能够安装接收到的APK文件，需要授予安装未知来源应用的权限。")
            .setPositiveButton("去设置") { _, _ ->
                requestInstallPermission()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
            requestInstallPermissionLauncher.launch(intent)
        }
    }

    private fun bindToService() {
        val intent = Intent(this, UpdateService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupServiceCallbacks() {
        updateService?.let { service ->
            service.setOnServiceStateChanged { isRunning ->
                runOnUiThread {
                    updateServiceStatus(isRunning)
                    if (isRunning) {
                        val ipAddress = NetworkUtils.getLocalIpAddress(this)
                        if (ipAddress != null) {
                            updateConnectionInfo(ipAddress, service.getCurrentPort())
                        }
                    }
                }
            }

            service.setOnClientConnected {
                runOnUiThread {
                    addLogMessage("🔗 客户端已连接")
                }
            }

            service.setOnClientDisconnected {
                runOnUiThread {
                    addLogMessage("🔌 客户端已断开连接")
                }
            }

            service.setOnProgress { message ->
                runOnUiThread {
                    addLogMessage("📥 $message")
                }
            }

            service.setOnError { error ->
                runOnUiThread {
                    addLogMessage("❌ $error")
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun toggleService() {
        updateService?.let { service ->
            if (service.isRunning()) {
                service.stopUpdateService()
                addLogMessage("🛑 正在停止服务...")
            } else {
                service.startUpdateService()
                addLogMessage("🚀 正在启动服务...")
            }
        }
    }

    private fun updateServiceStatus(isRunning: Boolean) {
        if (isRunning) {
            binding.tvServiceStatus.text = getString(R.string.status_running)
            binding.btnToggleService.text = getString(R.string.stop_service)
            binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.green_500))
        } else {
            binding.tvServiceStatus.text = getString(R.string.status_stopped)
            binding.btnToggleService.text = getString(R.string.start_service)
            binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.red_500))
        }
    }

    private fun updateConnectionInfo(ipAddress: String, port: Int) {
        binding.tvPort.text = port.toString()
        binding.tvWebSocketUrl.text = getString(R.string.websocket_url, ipAddress, port)
        binding.tvTcpUrl.text = getString(R.string.tcp_url, ipAddress, port)
    }

    private fun updateUI() {
        updateService?.let { service ->
            updateServiceStatus(service.isRunning())
            if (service.isRunning()) {
                val ipAddress = NetworkUtils.getLocalIpAddress(this)
                if (ipAddress != null) {
                    updateConnectionInfo(ipAddress, service.getCurrentPort())
                }
            }
        }
    }

    private fun addLogMessage(message: String) {
        lifecycleScope.launch {
            val timestamp = timeFormat.format(Date())
            val logMessage = "[$timestamp] $message"
            
            val currentLog = binding.tvActivityLog.text.toString()
            val newLog = if (currentLog.isEmpty()) {
                logMessage
            } else {
                "$currentLog\n$logMessage"
            }
            
            binding.tvActivityLog.text = newLog
            
            // Auto-scroll to bottom
            binding.tvActivityLog.post {
                val scrollView = binding.tvActivityLog.parent as? android.widget.ScrollView
                scrollView?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun registerInstallResultReceiver() {
        val filter = IntentFilter().apply {
            addAction("com.tableos.superupdate.INSTALL_SUCCESS")
            addAction("com.tableos.superupdate.INSTALL_FAILURE")
            addAction("com.tableos.superupdate.INSTALL_ABORTED")
            addAction("com.tableos.superupdate.INSTALL_BLOCKED")
        }
        registerReceiver(installResultReceiver, filter)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1002 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    addLogMessage("✅ 通知权限已授予")
                } else {
                    addLogMessage("⚠️ 通知权限被拒绝")
                }
            }
        }
    }
}