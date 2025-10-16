#!/usr/bin/env python3
"""
Super Update Remote Client
用于连接到Android设备并推送APK文件进行安装的CLI工具
"""

import asyncio
import websockets
import json
import os
import sys
import argparse
from pathlib import Path
import hashlib
from typing import Optional

class SuperUpdateClient:
    def __init__(self, host: str, port: int = 8080):
        self.host = host
        self.port = port
        self.websocket = None
        self.chunk_size = 64 * 1024  # 64KB chunks
        
    async def connect(self) -> bool:
        """连接到WebSocket服务器"""
        try:
            uri = f"ws://{self.host}:{self.port}"
            print(f"正在连接到 {uri}...")
            self.websocket = await websockets.connect(uri)
            print("✅ 连接成功!")
            
            # 等待并处理服务器的连接响应
            connected_response = await self.wait_for_response()
            if connected_response and connected_response.get("type") == "connected":
                await self._handle_response(connected_response)
                return True
            else:
                print(f"❌ 未收到预期的连接响应: {connected_response}")
                return False
            
        except Exception as e:
            print(f"❌ 连接失败: {e}")
            return False
    
    async def disconnect(self):
        """断开连接"""
        if self.websocket:
            await self.websocket.close()
            self.websocket = None
            print("🔌 连接已断开")
    
    def calculate_file_hash(self, file_path: str) -> str:
        """计算文件MD5哈希值"""
        hash_md5 = hashlib.md5()
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(4096), b""):
                hash_md5.update(chunk)
        return hash_md5.hexdigest()
    
    async def send_file_info(self, file_path: str) -> bool:
        """发送文件信息"""
        try:
            file_size = os.path.getsize(file_path)
            file_name = os.path.basename(file_path)
            
            # 服务器期望格式: FILE_INFO:filename:filesize
            file_info_command = f"FILE_INFO:{file_name}:{file_size}"
            
            await self.websocket.send(file_info_command)
            print(f"📄 文件信息已发送: {file_name} ({file_size} bytes)")
            return True
            
        except Exception as e:
            print(f"❌ 发送文件信息失败: {e}")
            return False
    
    async def send_file_data(self, file_path: str) -> bool:
        """发送文件数据"""
        try:
            file_size = os.path.getsize(file_path)
            sent_bytes = 0
            
            print(f"📤 开始传输文件数据...")
            
            with open(file_path, "rb") as f:
                while True:
                    chunk = f.read(self.chunk_size)
                    if not chunk:
                        break
                    
                    await self.websocket.send(chunk)
                    sent_bytes += len(chunk)
                    
                    # 显示进度
                    progress = (sent_bytes / file_size) * 100
                    print(f"\r📥 传输进度: {progress:.1f}% ({sent_bytes}/{file_size} bytes)", end="")
            
            print("\n✅ 文件数据传输完成")
            return True
            
        except Exception as e:
            print(f"\n❌ 文件数据传输失败: {e}")
            return False
    
    async def send_transfer_complete(self) -> bool:
        """发送传输完成信号"""
        try:
            # 服务器期望格式: TRANSFER_COMPLETE
            await self.websocket.send("TRANSFER_COMPLETE")
            print("✅ 传输完成信号已发送")
            return True
            
        except Exception as e:
            print(f"❌ 发送完成信号失败: {e}")
            return False
    
    async def wait_for_response(self, timeout: int = 30) -> Optional[dict]:
        """等待服务器响应"""
        try:
            print(f"⏳ 等待服务器响应 (超时: {timeout}秒)...")
            response = await asyncio.wait_for(self.websocket.recv(), timeout=timeout)
            
            try:
                return json.loads(response)
            except json.JSONDecodeError:
                print(f"⚠️ 收到非JSON响应: {response}")
                return {"type": "unknown", "message": response}
                
        except asyncio.TimeoutError:
            print("⏰ 等待响应超时")
            return None
        except Exception as e:
            print(f"❌ 接收响应失败: {e}")
            return None
    
    async def _handle_response(self, response: dict) -> bool:
        """处理服务器响应的辅助方法"""
        if not response:
            return False
            
        response_type = response.get("type")
        message = response.get("message", "")
        
        # 处理JSON响应
        if response_type == "connected":
            print(f"🔗 服务器连接成功: {message}")
            return True
        elif response_type == "ready":
            print(f"✅ 服务器准备就绪: {message}")
            return True
        elif response_type == "progress":
            progress = response.get("progress", 0)
            received = response.get("received", 0)
            total = response.get("total", 0)
            print(f"📊 传输进度: {progress}% ({received}/{total} bytes)")
            return True  # 进度消息不是最终响应，继续等待
        elif response_type == "broadcast":
            print(f"📢 服务器广播: {message}")
            return True  # 广播消息不是最终响应，继续等待
        elif response_type == "success":
            print("🎉 APK上传并安装成功!")
            return True
        elif response_type == "error":
            print(f"❌ 服务器错误: {message}")
            return False
        # 处理旧格式的文本响应（向后兼容）
        elif response_type == "unknown":
            if message == "TRANSFER_SUCCESS":
                print("🎉 APK上传并安装成功!")
                return True
            elif message.startswith("ERROR:") or message.startswith("COMMAND_ERROR:"):
                print(f"❌ 服务器错误: {message}")
                return False
            else:
                print(f"⚠️ 未知响应: {message}")
                return False
        else:
            print(f"⚠️ 未知响应类型: {response}")
            return False
    
    async def upload_apk(self, apk_path: str) -> bool:
        """上传APK文件"""
        if not os.path.exists(apk_path):
            print(f"❌ 文件不存在: {apk_path}")
            return False
        
        if not apk_path.lower().endswith('.apk'):
            print(f"⚠️ 警告: 文件不是APK格式: {apk_path}")
        
        print(f"🚀 开始上传APK: {apk_path}")
        
        # 1. 发送文件信息
        if not await self.send_file_info(apk_path):
            return False
        
        # 1.1. 等待服务器ready响应
        ready_response = await self.wait_for_response()
        if not ready_response or ready_response.get("type") != "ready":
            print(f"❌ 服务器未准备好接收数据: {ready_response}")
            return False
        await self._handle_response(ready_response)
        
        # 2. 发送文件数据
        if not await self.send_file_data(apk_path):
            return False
        
        # 3. 发送传输完成信号
        if not await self.send_transfer_complete():
            return False
        
        # 4. 等待服务器最终响应（可能有多个进度响应）
        while True:
            response = await self.wait_for_response()
            if not response:
                print("❌ 未收到服务器响应")
                return False
            
            response_type = response.get("type")
            
            # 如果是进度响应，显示进度并继续等待
            if response_type == "progress":
                await self._handle_response(response)
                continue
            
            # 如果是最终响应（成功或错误），处理并返回结果
            return await self._handle_response(response)

async def main():
    parser = argparse.ArgumentParser(description="Super Update Remote Client")
    parser.add_argument("host", help="Android设备的IP地址")
    parser.add_argument("apk_file", help="要上传的APK文件路径")
    parser.add_argument("-p", "--port", type=int, default=8080, help="端口号 (默认: 8080)")
    
    args = parser.parse_args()
    
    # 验证APK文件
    if not os.path.exists(args.apk_file):
        print(f"❌ 错误: APK文件不存在: {args.apk_file}")
        sys.exit(1)
    
    # 创建客户端
    client = SuperUpdateClient(args.host, args.port)
    
    try:
        # 连接到设备
        if not await client.connect():
            sys.exit(1)
        
        # 上传APK
        success = await client.upload_apk(args.apk_file)
        
        if success:
            print("🎉 操作完成!")
            sys.exit(0)
        else:
            print("❌ 操作失败!")
            sys.exit(1)
            
    except KeyboardInterrupt:
        print("\n⚠️ 用户中断操作")
        sys.exit(1)
    except Exception as e:
        print(f"❌ 未预期的错误: {e}")
        sys.exit(1)
    finally:
        await client.disconnect()

if __name__ == "__main__":
    # 检查依赖
    try:
        import websockets
    except ImportError:
        print("❌ 缺少依赖: websockets")
        print("请运行: pip install websockets")
        sys.exit(1)
    
    asyncio.run(main())