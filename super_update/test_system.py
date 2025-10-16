#!/usr/bin/env python3
"""
Super Update System Test Script
测试整个系统的功能，包括权限检查和APK安装
"""

import os
import sys
import subprocess
import time
from pathlib import Path

def print_header(title):
    """打印标题"""
    print("\n" + "="*60)
    print(f" {title}")
    print("="*60)

def print_step(step, description):
    """打印步骤"""
    print(f"\n[步骤 {step}] {description}")
    print("-" * 40)

def check_adb():
    """检查ADB是否可用"""
    try:
        result = subprocess.run(['adb', 'version'], capture_output=True, text=True)
        if result.returncode == 0:
            print("✅ ADB 可用")
            return True
        else:
            print("❌ ADB 不可用")
            return False
    except FileNotFoundError:
        print("❌ ADB 未找到，请确保Android SDK已安装并添加到PATH")
        return False

def check_devices():
    """检查连接的设备"""
    try:
        result = subprocess.run(['adb', 'devices'], capture_output=True, text=True)
        lines = result.stdout.strip().split('\n')[1:]  # 跳过标题行
        devices = [line.split('\t')[0] for line in lines if line.strip() and 'device' in line]
        
        if devices:
            print(f"✅ 找到 {len(devices)} 个设备:")
            for device in devices:
                print(f"   - {device}")
            return devices
        else:
            print("❌ 未找到连接的设备")
            return []
    except Exception as e:
        print(f"❌ 检查设备失败: {e}")
        return []

def install_apk(apk_path, device=None):
    """安装APK到设备"""
    cmd = ['adb']
    if device:
        cmd.extend(['-s', device])
    cmd.extend(['install', '-r', apk_path])  # -r 表示替换已存在的应用
    
    try:
        print(f"正在安装 {apk_path}...")
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode == 0:
            print("✅ APK 安装成功")
            return True
        else:
            print(f"❌ APK 安装失败: {result.stderr}")
            return False
    except Exception as e:
        print(f"❌ 安装过程出错: {e}")
        return False

def start_app(package_name, activity_name, device=None):
    """启动应用"""
    cmd = ['adb']
    if device:
        cmd.extend(['-s', device])
    cmd.extend(['shell', 'am', 'start', '-n', f"{package_name}/{activity_name}"])
    
    try:
        print(f"正在启动应用 {package_name}...")
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode == 0:
            print("✅ 应用启动成功")
            return True
        else:
            print(f"❌ 应用启动失败: {result.stderr}")
            return False
    except Exception as e:
        print(f"❌ 启动过程出错: {e}")
        return False

def check_app_permissions(package_name, device=None):
    """检查应用权限"""
    cmd = ['adb']
    if device:
        cmd.extend(['-s', device])
    cmd.extend(['shell', 'dumpsys', 'package', package_name])
    
    try:
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode == 0:
            output = result.stdout
            print("📋 应用权限信息:")
            
            # 查找权限相关信息
            permissions = []
            lines = output.split('\n')
            in_permissions = False
            
            for line in lines:
                if 'requested permissions:' in line.lower():
                    in_permissions = True
                    continue
                elif in_permissions and line.strip().startswith('android.permission'):
                    permissions.append(line.strip())
                elif in_permissions and not line.strip():
                    break
            
            if permissions:
                for perm in permissions:
                    print(f"   - {perm}")
            else:
                print("   未找到权限信息")
            
            return True
        else:
            print(f"❌ 获取权限信息失败: {result.stderr}")
            return False
    except Exception as e:
        print(f"❌ 检查权限出错: {e}")
        return False

def main():
    """主函数"""
    print_header("Super Update 系统测试")
    
    # 检查APK文件
    apk_path = Path(__file__).parent / "build/outputs/apk/debug/super_update-debug.apk"
    if not apk_path.exists():
        print(f"❌ APK文件不存在: {apk_path}")
        print("请先运行构建命令: ./gradlew :super_update:assembleDebug")
        sys.exit(1)
    
    print(f"✅ 找到APK文件: {apk_path}")
    
    # 步骤1: 检查ADB
    print_step(1, "检查ADB环境")
    if not check_adb():
        sys.exit(1)
    
    # 步骤2: 检查设备
    print_step(2, "检查连接的设备")
    devices = check_devices()
    if not devices:
        print("\n请确保:")
        print("1. 设备已连接并开启USB调试")
        print("2. 已授权计算机进行调试")
        sys.exit(1)
    
    # 选择设备（如果有多个）
    device = devices[0] if len(devices) == 1 else None
    if len(devices) > 1:
        print(f"\n检测到多个设备，使用第一个: {devices[0]}")
        device = devices[0]
    
    # 步骤3: 安装APK
    print_step(3, "安装Super Update APK")
    if not install_apk(str(apk_path), device):
        sys.exit(1)
    
    # 步骤4: 检查权限
    print_step(4, "检查应用权限")
    package_name = "com.tableos.superupdate"
    check_app_permissions(package_name, device)
    
    # 步骤5: 启动应用
    print_step(5, "启动应用")
    activity_name = "com.tableos.superupdate.MainActivity"
    if start_app(package_name, activity_name, device):
        print("\n🎉 系统测试完成!")
        print("\n接下来可以:")
        print("1. 在设备上查看应用界面")
        print("2. 检查权限状态")
        print("3. 测试APK安装功能")
        print("4. 使用远程客户端推送APK文件")
    else:
        print("\n⚠️  应用安装成功但启动失败，请手动启动应用")

if __name__ == "__main__":
    main()