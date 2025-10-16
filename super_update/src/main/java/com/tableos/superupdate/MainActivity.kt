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
import android.view.KeyEvent
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
                "com.tableos.superupdate.INSTALL_CONFLICT" -> {
                    val error = intent.getStringExtra("error_message") ?: "安装冲突"
                    addLogMessage("⚠️ 安装冲突: $error")
                }
                "com.tableos.superupdate.INSTALL_INCOMPATIBLE" -> {
                    val error = intent.getStringExtra("error_message") ?: "应用不兼容"
                    addLogMessage("❌ 应用不兼容: $error")
                }
                "com.tableos.superupdate.INSTALL_INVALID" -> {
                    val error = intent.getStringExtra("error_message") ?: "无效的APK"
                    addLogMessage("❌ 无效的APK: $error")
                }
                "com.tableos.superupdate.INSTALL_STORAGE_ERROR" -> {
                    val error = intent.getStringExtra("error_message") ?: "存储错误"
                    addLogMessage("❌ 存储错误: $error")
                }
                "com.tableos.superupdate.INSTALL_UNKNOWN" -> {
                    val error = intent.getStringExtra("error_message") ?: "未知状态"
                    val status = intent.getIntExtra("status", -1)
                    addLogMessage("❓ 未知安装状态($status): $error")
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
        
        // 确保Activity能够接收按键事件
        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        
        setupUI()
        checkPermissions()
        bindToService()
        registerInstallResultReceiver()
        
        Log.d(TAG, "MainActivity created and focus requested")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        unregisterReceiver(installResultReceiver)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        Log.d(TAG, "dispatchKeyEvent: action=${event?.action}, keyCode=${event?.keyCode}")
        
        // 只处理按键按下事件
        if (event?.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    Log.d(TAG, "Remote control key pressed: DPAD_CENTER or ENTER")
                    addLogMessage("🎮 遥控器操作：切换服务状态")
                    toggleService()
                    return true
                }
            }
        }
        
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "onKeyDown: keyCode=$keyCode")
        addLogMessage("🔑 按键事件: keyCode=$keyCode")
        
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // 遥控器确定键：开启/停止服务
                Log.d(TAG, "Remote control key pressed: DPAD_CENTER or ENTER")
                toggleService()
                addLogMessage("🎮 遥控器操作：${if (updateService?.isRunning() == true) "停止" else "启动"}服务")
                true
            }
            else -> {
                Log.d(TAG, "Other key pressed: $keyCode")
                super.onKeyDown(keyCode, event)
            }
        }
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
            addAction("com.tableos.superupdate.INSTALL_CONFLICT")
            addAction("com.tableos.superupdate.INSTALL_INCOMPATIBLE")
            addAction("com.tableos.superupdate.INSTALL_INVALID")
            addAction("com.tableos.superupdate.INSTALL_STORAGE_ERROR")
            addAction("com.tableos.superupdate.INSTALL_UNKNOWN")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(installResultReceiver, filter)
        }
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