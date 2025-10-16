# Super Update Remote Client

这是Super Update应用的远程客户端工具，用于从计算机向Android设备推送APK文件进行安装。

## 安装依赖

```bash
pip install -r requirements.txt
```

或者直接安装：

```bash
pip install websockets
```

## 使用方法

### 基本用法

```bash
python super_update_client.py <设备IP地址> <APK文件路径>
```

### 指定端口

```bash
python super_update_client.py <设备IP地址> <APK文件路径> -p <端口号>
```

### 示例

```bash
# 使用默认端口8080
python super_update_client.py 192.168.1.100 my_app.apk

# 使用自定义端口
python super_update_client.py 192.168.1.100 my_app.apk -p 9090
```

## 使用步骤

1. **启动Android应用**
   - 在Android设备上启动Super Update应用
   - 点击"启动服务"按钮
   - 记下显示的IP地址和端口号

2. **准备APK文件**
   - 确保要安装的APK文件在计算机上可访问
   - 建议使用绝对路径或确保文件在当前目录

3. **运行客户端**
   - 在计算机上打开终端/命令行
   - 运行客户端命令，指定设备IP和APK文件路径

4. **等待完成**
   - 客户端会显示传输进度
   - Android设备会自动安装APK
   - 安装结果会在两端显示

## 功能特性

- ✅ WebSocket连接，稳定可靠
- ✅ 文件完整性校验（MD5）
- ✅ 实时传输进度显示
- ✅ 错误处理和重试机制
- ✅ 支持大文件传输（分块传输）
- ✅ 跨平台支持（Windows/macOS/Linux）

## 故障排除

### 连接失败
- 确保Android设备和计算机在同一网络
- 检查IP地址是否正确
- 确认Android应用的服务已启动
- 检查防火墙设置

### 传输失败
- 检查网络连接稳定性
- 确保APK文件完整且未损坏
- 检查设备存储空间是否充足

### 安装失败
- 确保Android设备已授予"安装未知来源应用"权限
- 检查APK文件是否与设备架构兼容
- 确认APK文件签名有效

## 技术细节

- **协议**: WebSocket over TCP
- **传输**: 二进制数据分块传输
- **校验**: MD5文件完整性验证
- **分块大小**: 64KB
- **超时时间**: 30秒

## 安全注意事项

- 仅在可信网络环境中使用
- 确保传输的APK文件来源可靠
- 建议使用VPN或局域网进行传输
- 定期更新客户端和服务端代码