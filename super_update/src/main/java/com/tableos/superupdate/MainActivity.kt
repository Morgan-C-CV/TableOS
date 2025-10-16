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
        private const val REQUEST_DEVICE_ADMIN = 1002
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
        
        // 启动时自动执行网络诊断
        performStartupNetworkDiagnostics()
        
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

        binding.btnNetworkDiagnostics.setOnClickListener {
            performNetworkDiagnostics()
        }

        // Display IP address
        val ipAddress = NetworkUtils.getLocalIpAddress(this)
        if (ipAddress != null) {
            updateConnectionInfo(ipAddress, null, 8080) // Default port, will be updated when service starts
        } else {
            updateConnectionInfo(null, null, 8080)
            addLogMessage("⚠️ 无法获取本机IP地址，请检查网络连接")
        }

        // Initial UI state
        updateServiceStatus(false)
        addLogMessage("📱 Super Update 已启动")
        addLogMessage("💡 点击'启动服务'开始监听远程连接")
    }

    private fun checkPermissions() {
        // 使用新的权限管理工具检查所有权限
        val permissionStatus = PermissionUtils.checkAllPermissions(this)
        
        // 显示权限详情
        addLogMessage("=== 权限检查 ===")
        addLogMessage("安装权限: ${if (permissionStatus.hasInstallPermission) "✅" else "❌"}")
        addLogMessage("存储权限: ${if (permissionStatus.hasStoragePermission) "✅" else "❌"}")
        addLogMessage("Root权限: ${if (permissionStatus.hasRootPermission) "✅" else "❌"}")
        addLogMessage("设备Root: ${if (permissionStatus.isDeviceRooted) "✅" else "❌"}")
        addLogMessage("设备管理员: ${if (permissionStatus.hasDeviceAdminPermission) "✅" else "❌"}")
        
        // 显示安装能力
        val capabilities = ApkInstaller.getInstallCapabilities(this)
        addLogMessage(capabilities)
        
        // 请求缺失的权限
        if (!permissionStatus.hasStoragePermission) {
            addLogMessage("⚠️ 请求存储权限...")
            PermissionUtils.requestStoragePermission(this, REQUEST_INSTALL_PACKAGES)
        }
        
        if (!permissionStatus.hasInstallPermission) {
            addLogMessage("⚠️ 需要安装权限...")
            showInstallPermissionDialog()
        }
        
        // 请求设备管理员权限
        if (!permissionStatus.hasDeviceAdminPermission) {
            addLogMessage("⚠️ 请求设备管理员权限...")
            showDeviceAdminPermissionDialog()
        }
        
        // 尝试获取系统级权限
        if (permissionStatus.hasRootPermission) {
            addLogMessage("🔧 尝试获取系统级权限...")
            val systemPermResult = PermissionUtils.tryGrantSystemPermissions(this)
            addLogMessage("系统权限: ${if (systemPermResult) "✅ 成功" else "❌ 失败"}")
        }
    }

    private fun showInstallPermissionDialog() {
        val permissionStatus = PermissionUtils.checkAllPermissions(this)
        
        val message = buildString {
            appendLine("应用需要安装权限来安装APK文件。")
            appendLine()
            appendLine("当前状态:")
            appendLine("• 安装权限: ${if (permissionStatus.hasInstallPermission) "✅ 已授予" else "❌ 未授予"}")
            appendLine("• Root权限: ${if (permissionStatus.hasRootPermission) "✅ 已获得" else "❌ 未获得"}")
            appendLine("• 系统应用: ${if (PermissionUtils.isSystemApp(this@MainActivity)) "✅ 是" else "❌ 否"}")
            appendLine()
            if (permissionStatus.hasRootPermission) {
                appendLine("✨ 检测到Root权限，可以进行静默安装")
            } else {
                appendLine("请在设置中允许此应用安装未知来源的应用。")
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle("安装权限")
            .setMessage(message)
            .setPositiveButton("去设置") { _, _ ->
                PermissionUtils.requestInstallPermission(this)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showDeviceAdminPermissionDialog() {
        val message = buildString {
            appendLine("应用需要设备管理员权限来进行系统级操作。")
            appendLine()
            appendLine("设备管理员权限可以让应用:")
            appendLine("• 静默安装APK文件")
            appendLine("• 执行系统级操作")
            appendLine("• 管理设备策略")
            appendLine()
            appendLine("这将提高应用的安装成功率和功能完整性。")
        }
        
        AlertDialog.Builder(this)
            .setTitle("设备管理员权限")
            .setMessage(message)
            .setPositiveButton("授予权限") { _, _ ->
                PermissionUtils.requestDeviceAdminPermission(this, REQUEST_DEVICE_ADMIN)
            }
            .setNegativeButton("跳过", null)
            .show()
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
                        val localIp = service.getLocalIpAddress()
                        val publicIp = service.getPublicIpAddress()
                        updateConnectionInfo(localIp, publicIp, service.getCurrentPort())
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

    private fun updateConnectionInfo(localIp: String?, publicIp: String?, port: Int) {
        // 构建简化的网络地址显示文本
        val localText = if (localIp != null) "$localIp:$port" else "获取失败"
        val publicText = if (publicIp != null) "$publicIp:$port" else "获取中..."
        
        val networkAddresses = "局域网: $localText | 公网: $publicText"
        binding.tvNetworkAddresses.text = networkAddresses
        
        // 记录日志
        if (publicIp != null) {
            addLogMessage("✅ 公网IP获取成功: $publicIp")
        } else if (localIp == null) {
            addLogMessage("⚠️ 无法获取本机IP地址，请检查网络连接")
        }
    }

    private fun updateUI() {
        updateService?.let { service ->
            updateServiceStatus(service.isRunning())
            if (service.isRunning()) {
                val localIp = service.getLocalIpAddress()
                val publicIp = service.getPublicIpAddress()
                updateConnectionInfo(localIp, publicIp, service.getCurrentPort())
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

    private fun performNetworkDiagnostics() {
        addLogMessage("🔍 开始网络诊断...")
        
        lifecycleScope.launch {
            try {
                // 执行网络诊断
                val diagnosticsResult = NetworkUtils.performNetworkDiagnostics(this@MainActivity)
                
                // 将诊断结果分行显示在日志中
                val lines = diagnosticsResult.split("\n")
                for (line in lines) {
                    if (line.isNotBlank()) {
                        addLogMessage("📊 $line")
                    }
                }
                
                // 尝试获取公网IP
                addLogMessage("🌐 尝试获取公网IP地址...")
                val publicIp = NetworkUtils.getPublicIpAddress()
                if (publicIp != null) {
                    addLogMessage("✅ 公网IP获取成功: $publicIp")
                } else {
                    addLogMessage("❌ 公网IP获取失败")
                }
                
                addLogMessage("🔍 网络诊断完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "网络诊断失败", e)
                addLogMessage("❌ 网络诊断失败: ${e.message}")
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_DEVICE_ADMIN -> {
                if (PermissionUtils.hasDeviceAdminPermission(this)) {
                    addLogMessage("✅ 设备管理员权限已授予")
                } else {
                    addLogMessage("❌ 设备管理员权限被拒绝")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_INSTALL_PACKAGES -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    addLogMessage("✅ 存储权限已授予")
                } else {
                    addLogMessage("❌ 存储权限被拒绝")
                }
            }
        }
    }

    /**
     * 启动时自动执行网络诊断
     */
    private fun performStartupNetworkDiagnostics() {
        addLogMessage("🔍 启动时自动执行网络诊断...")
        
        lifecycleScope.launch {
            try {
                // 获取局域网IP
                val localIp = NetworkUtils.getLocalIpAddress(this@MainActivity)
                
                // 获取公网IP
                val publicIp = NetworkUtils.getPublicIpAddress()
                
                if (!publicIp.isNullOrEmpty()) {
                    // 成功获取公网IP，立即更新UI
                    updateConnectionInfo(localIp, publicIp, 0)
                    addLogMessage("✅ 启动诊断成功 - 局域网: $localIp, 公网: $publicIp")
                    Log.i(TAG, "✅ 启动时成功获取公网IP: $publicIp")
                } else {
                    updateConnectionInfo(localIp, "获取失败", 0)
                    addLogMessage("⚠️ 启动诊断 - 局域网: $localIp, 公网IP获取失败")
                }
            } catch (e: Exception) {
                addLogMessage("❌ 启动诊断异常: ${e.message}")
                Log.e(TAG, "启动时网络诊断异常", e)
            }
        }
    }
}