#!/usr/bin/env python3
"""
测试公网IP获取功能
模拟Android应用中的公网IP获取逻辑
"""

import requests
import time
import json

def test_public_ip_services():
    """测试各个公网IP服务"""
    services = [
        "https://api.ipify.org",
        "https://checkip.amazonaws.com", 
        "https://icanhazip.com",
        "https://ipinfo.io/ip",
        "https://httpbin.org/ip"
    ]
    
    print("🔍 测试公网IP获取服务...")
    print("=" * 50)
    
    successful_ips = []
    
    for i, service in enumerate(services, 1):
        try:
            print(f"\n📡 测试服务 {i}/{len(services)}: {service}")
            
            headers = {'User-Agent': 'SuperUpdate/1.0'}
            response = requests.get(service, headers=headers, timeout=8)
            
            if response.status_code == 200:
                content = response.text.strip()
                print(f"   原始响应: {content}")
                
                # 处理httpbin.org的JSON响应
                if "httpbin.org" in service and content.startswith("{"):
                    try:
                        data = json.loads(content)
                        ip = data.get("origin", "").split(",")[0].strip()
                    except:
                        ip = content
                else:
                    ip = content
                
                # 验证IP格式
                if is_valid_ip(ip):
                    print(f"   ✅ 成功获取IP: {ip}")
                    successful_ips.append(ip)
                else:
                    print(f"   ❌ 无效IP格式: {ip}")
            else:
                print(f"   ❌ HTTP错误: {response.status_code}")
                
        except requests.exceptions.Timeout:
            print(f"   ⏰ 请求超时")
        except requests.exceptions.ConnectionError:
            print(f"   🔌 连接错误")
        except Exception as e:
            print(f"   ❌ 其他错误: {e}")
    
    print("\n" + "=" * 50)
    print("📊 测试结果汇总:")
    
    if successful_ips:
        # 统计IP出现次数
        ip_counts = {}
        for ip in successful_ips:
            ip_counts[ip] = ip_counts.get(ip, 0) + 1
        
        print(f"✅ 成功获取到 {len(successful_ips)} 个IP地址")
        for ip, count in ip_counts.items():
            print(f"   IP: {ip} (出现 {count} 次)")
        
        # 选择最常见的IP
        most_common_ip = max(ip_counts.items(), key=lambda x: x[1])
        print(f"\n🎯 推荐使用IP: {most_common_ip[0]}")
        
        return most_common_ip[0]
    else:
        print("❌ 所有服务都无法获取公网IP")
        print("💡 可能的原因:")
        print("   - 网络连接问题")
        print("   - 防火墙阻止")
        print("   - 代理设置问题")
        print("   - 服务暂时不可用")
        return None

def is_valid_ip(ip):
    """验证IP地址格式"""
    try:
        parts = ip.split('.')
        if len(parts) != 4:
            return False
        for part in parts:
            if not (0 <= int(part) <= 255):
                return False
        return True
    except:
        return False

def test_network_connectivity():
    """测试网络连通性"""
    print("\n🌐 测试网络连通性...")
    
    test_urls = [
        "https://www.google.com",
        "https://www.baidu.com", 
        "https://httpbin.org/get"
    ]
    
    for url in test_urls:
        try:
            response = requests.get(url, timeout=5)
            if response.status_code == 200:
                print(f"   ✅ {url} - 连接正常")
                return True
            else:
                print(f"   ⚠️ {url} - HTTP {response.status_code}")
        except Exception as e:
            print(f"   ❌ {url} - {e}")
    
    return False

if __name__ == "__main__":
    print("🚀 Super Update 公网IP获取测试")
    print("=" * 50)
    
    # 测试网络连通性
    if not test_network_connectivity():
        print("\n❌ 网络连接异常，请检查网络设置")
        exit(1)
    
    # 测试公网IP获取
    public_ip = test_public_ip_services()
    
    if public_ip:
        print(f"\n🎉 测试完成！您的公网IP是: {public_ip}")
        print(f"📱 Android应用应该能够获取到相同的IP地址")
    else:
        print(f"\n😞 测试失败！Android应用可能也无法获取公网IP")
        print(f"🔧 建议检查网络设置和防火墙配置")