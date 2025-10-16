# Super Update 模块

Super Update 是一个用于 TableOS 的远程应用更新模块，支持通过 WebSocket 连接远程推送和安装 APK 文件。

## 功能特性

- 🌐 **WebSocket 服务器**: 在 Android 设备上运行 WebSocket 服务器
- 📱 **APK 远程推送**: 支持从远程客户端推送 APK 文件
- 🔧 **自动安装**: 自动安装接收到的 APK 文件
- 📊 **实时进度**: 显示文件传输和安装进度
- 🔒 **权限管理**: 自动请求必要的安装权限
- 📝 **活动日志**: 记录所有连接和安装活动

## 项目结构

```
super_update/
├── src/main/java/com/tableos/superupdate/
│   ├── MainActivity.kt              # 主界面
│   ├── UpdateService.kt             # 更新服务
│   ├── WebSocketServer.kt           # WebSocket 服务器
│   ├── NetworkUtils.kt              # 网络工具
│   └── InstallResultReceiver.kt     # 安装结果接收器
├── remote_client/                   # 远程客户端
│   ├── super_update_client.py       # Python 客户端
│   ├── requirements.txt             # Python 依赖
│   └── README.md                    # 客户端说明
├── test_apk/                        # 测试 APK
└── test_script.py                   # 自动化测试脚本
```

## 快速开始

### 1. 构建项目

```bash
# 在 TableOS 根目录下
./gradlew :super_update:assembleDebug
```

### 2. 安装到设备

```bash
adb install super_update/build/outputs/apk/debug/super_update-debug.apk
```

### 3. 启动应用

在设备上启动 "Super Update" 应用，应用会：
- 自动启动 WebSocket 服务器（默认端口 8080）
- 显示设备 IP 地址和连接状态
- 请求必要的权限（安装未知应用、通知权限）

### 4. 使用远程客户端

```bash
cd super_update/remote_client
pip install -r requirements.txt
python super_update_client.py <设备IP> <APK文件路径>
```

## 自动化测试

运行自动化测试脚本：

```bash
cd super_update
python test_script.py
```

测试脚本会：
1. 检查 ADB 连接
2. 安装 super_update APK
3. 启动应用
4. 测试 WebSocket 连接
5. 提供 APK 推送测试指导

## 使用示例

### Android 端

1. 启动 Super Update 应用
2. 确保设备连接到 WiFi 网络
3. 记录显示的 IP 地址
4. 保持应用在前台运行

### 远程客户端

```bash
# 基本用法
python super_update_client.py 192.168.1.100 /path/to/app.apk

# 指定端口
python super_update_client.py 192.168.1.100 /path/to/app.apk --port 8080

# 详细输出
python super_update_client.py 192.168.1.100 /path/to/app.apk --verbose
```

## 权限要求

### Android 权限

- `INTERNET`: 网络通信
- `ACCESS_NETWORK_STATE`: 检查网络状态
- `ACCESS_WIFI_STATE`: 检查 WiFi 状态
- `REQUEST_INSTALL_PACKAGES`: 安装 APK 文件
- `POST_NOTIFICATIONS`: 显示通知（Android 13+）

### 运行时权限

应用会自动请求以下权限：
- 安装未知应用权限
- 通知权限（Android 13+）

## 技术细节

### WebSocket 协议

客户端与服务器通过 WebSocket 通信，支持以下消息类型：

1. **文件信息** (JSON):
```json
{
  "type": "file_info",
  "filename": "app.apk",
  "size": 1234567,
  "md5": "abc123..."
}
```

2. **文件数据** (Binary): 
   - 直接发送二进制数据块

3. **传输完成** (JSON):
```json
{
  "type": "transfer_complete"
}
```

### 网络配置

- 默认端口: 8080
- 支持自动端口查找（8080-8090）
- 仅支持 WiFi 网络连接
- 自动获取本地 IP 地址

## 故障排除

### 常见问题

1. **无法连接到 WebSocket 服务器**
   - 确保设备和客户端在同一网络
   - 检查防火墙设置
   - 确认应用已启动且在前台

2. **APK 安装失败**
   - 检查是否已授予"安装未知应用"权限
   - 确认 APK 文件完整性
   - 检查设备存储空间

3. **权限被拒绝**
   - 手动在设置中授予权限
   - 重启应用重新请求权限

### 调试模式

启用详细日志输出：
```bash
adb logcat | grep "SuperUpdate"
```

## 安全注意事项

- 仅在受信任的网络环境中使用
- 确保推送的 APK 文件来源可靠
- 定期检查设备上安装的应用
- 建议在测试环境中使用

## 开发说明

### 添加新功能

1. 修改 `WebSocketServer.kt` 添加新的消息处理
2. 更新 `MainActivity.kt` 添加 UI 元素
3. 修改远程客户端支持新功能
4. 更新测试脚本

### 自定义配置

可以通过修改以下文件自定义配置：
- `NetworkUtils.kt`: 网络相关配置
- `UpdateService.kt`: 服务配置
- `strings.xml`: 界面文本

## 许可证

本项目是 TableOS 的一部分，遵循项目整体许可证。