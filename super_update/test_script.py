#!/usr/bin/env python3
"""
测试脚本：验证super_update模块的功能
"""

import os
import sys
import time
import subprocess
import asyncio
import websockets
import json

def check_adb_connection():
    """检查ADB连接"""
    try:
        result = subprocess.run(['adb', 'devices'], capture_output=True, text=True)
        lines = result.stdout.strip().split('\n')[1:]  # 跳过标题行
        devices = [line.split('\t')[0] for line in lines if '\tdevice' in line]
        return devices
    except FileNotFoundError:
        print("❌ ADB未找到，请确保Android SDK已安装并添加到PATH")
        return []

def install_apk(apk_path, device_id=None):
    """安装APK到设备"""
    cmd = ['adb']
    if device_id:
        cmd.extend(['-s', device_id])
    cmd.extend(['install', '-r', apk_path])
    
    try:
        result = subprocess.run(cmd, capture_output=True, text=True)
        return result.returncode == 0, result.stdout + result.stderr
    except Exception as e:
        return False, str(e)

def start_app(package_name, activity_name, device_id=None):
    """启动应用"""
    cmd = ['adb']
    if device_id:
        cmd.extend(['-s', device_id])
    cmd.extend(['shell', 'am', 'start', '-n', f'{package_name}/{activity_name}'])
    
    try:
        result = subprocess.run(cmd, capture_output=True, text=True)
        return result.returncode == 0, result.stdout + result.stderr
    except Exception as e:
        return False, str(e)

def get_device_ip(device_id=None):
    """获取设备IP地址"""
    cmd = ['adb']
    if device_id:
        cmd.extend(['-s', device_id])
    cmd.extend(['shell', 'ip', 'route', 'get', '1.1.1.1'])
    
    try:
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode == 0:
            # 解析输出获取IP
            for line in result.stdout.split('\n'):
                if 'src' in line:
                    parts = line.split()
                    for i, part in enumerate(parts):
                        if part == 'src' and i + 1 < len(parts):
                            return parts[i + 1]
        return None
    except Exception as e:
        print(f"获取设备IP失败: {e}")
        return None

async def test_websocket_connection(ip, port=8080):
    """测试WebSocket连接"""
    uri = f"ws://{ip}:{port}"
    try:
        async with websockets.connect(uri, timeout=5) as websocket:
            # 发送测试消息
            test_message = {"type": "ping", "timestamp": time.time()}
            await websocket.send(json.dumps(test_message))
            
            # 等待响应
            response = await asyncio.wait_for(websocket.recv(), timeout=5)
            print(f"✅ WebSocket连接成功，响应: {response}")
            return True
    except Exception as e:
        print(f"❌ WebSocket连接失败: {e}")
        return False

def main():
    print("🚀 开始测试super_update模块...")
    
    # 1. 检查ADB连接
    print("\n1. 检查ADB连接...")
    devices = check_adb_connection()
    if not devices:
        print("❌ 没有找到连接的设备")
        return False
    
    device_id = devices[0] if len(devices) == 1 else None
    if len(devices) > 1:
        print(f"找到多个设备: {devices}")
        device_id = input("请选择设备ID (直接回车选择第一个): ").strip() or devices[0]
    
    print(f"✅ 使用设备: {device_id or devices[0]}")
    
    # 2. 安装super_update APK
    print("\n2. 安装super_update APK...")
    apk_path = "super_update/build/outputs/apk/debug/super_update-debug.apk"
    if not os.path.exists(apk_path):
        print(f"❌ APK文件不存在: {apk_path}")
        return False
    
    success, output = install_apk(apk_path, device_id)
    if success:
        print("✅ super_update APK安装成功")
    else:
        print(f"❌ APK安装失败: {output}")
        return False
    
    # 3. 启动应用
    print("\n3. 启动super_update应用...")
    success, output = start_app("com.tableos.superupdate", "com.tableos.superupdate.MainActivity", device_id)
    if success:
        print("✅ 应用启动成功")
    else:
        print(f"❌ 应用启动失败: {output}")
        return False
    
    # 4. 等待应用启动
    print("\n4. 等待应用完全启动...")
    time.sleep(3)
    
    # 5. 获取设备IP
    print("\n5. 获取设备IP地址...")
    device_ip = get_device_ip(device_id)
    if device_ip:
        print(f"✅ 设备IP: {device_ip}")
    else:
        print("❌ 无法获取设备IP，请手动检查")
        device_ip = input("请手动输入设备IP地址: ").strip()
        if not device_ip:
            return False
    
    # 6. 测试WebSocket连接
    print(f"\n6. 测试WebSocket连接 (ws://{device_ip}:8080)...")
    try:
        success = asyncio.run(test_websocket_connection(device_ip))
        if success:
            print("✅ WebSocket服务器运行正常")
        else:
            print("❌ WebSocket连接失败，请检查应用是否正确启动")
            return False
    except Exception as e:
        print(f"❌ WebSocket测试异常: {e}")
        return False
    
    # 7. 测试APK推送
    print(f"\n7. 测试APK推送功能...")
    test_apk_path = "super_update/test_apk/build/outputs/apk/debug/test_apk-debug.apk"
    if os.path.exists(test_apk_path):
        print(f"使用测试APK: {test_apk_path}")
        print(f"可以使用以下命令测试APK推送:")
        print(f"cd super_update/remote_client")
        print(f"python super_update_client.py {device_ip} {test_apk_path}")
    else:
        print(f"❌ 测试APK不存在: {test_apk_path}")
    
    print("\n🎉 super_update模块基本功能测试完成！")
    return True

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)