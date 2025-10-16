package com.tableos.superupdate

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class UpdateWebSocketServer(
    address: InetSocketAddress,
    private val onClientConnected: () -> Unit,
    private val onClientDisconnected: () -> Unit,
    private val onApkReceived: (File) -> Unit,
    private val onProgress: (String) -> Unit,
    private val onError: (String) -> Unit
) : WebSocketServer(address) {

    companion object {
        private const val TAG = "UpdateWebSocketServer"
        private const val COMMAND_START_TRANSFER = "START_TRANSFER"
        private const val COMMAND_FILE_INFO = "FILE_INFO"
        private const val COMMAND_FILE_DATA = "FILE_DATA"
        private const val COMMAND_TRANSFER_COMPLETE = "TRANSFER_COMPLETE"
        private const val COMMAND_ERROR = "ERROR"
    }

    private val clientSessions = ConcurrentHashMap<WebSocket, ClientSession>()

    data class ClientSession(
        var isReceivingFile: Boolean = false,
        var fileName: String? = null,
        var fileSize: Long = 0,
        var receivedBytes: Long = 0,
        var fileOutputStream: FileOutputStream? = null,
        var tempFile: File? = null
    )

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
        clientSessions[conn] = ClientSession()
        onClientConnected()
        onProgress("客户端已连接: ${conn.remoteSocketAddress}")
        
        // Send welcome message in JSON format
        conn.send("{\"type\":\"connected\",\"message\":\"Super Update Server Ready\"}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "Client disconnected: ${conn.remoteSocketAddress}, reason: $reason")
        
        // Clean up session
        clientSessions[conn]?.let { session ->
            session.fileOutputStream?.close()
            session.tempFile?.delete()
        }
        clientSessions.remove(conn)
        
        onClientDisconnected()
        onProgress("客户端已断开连接")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d(TAG, "Received text message: $message")
        handleTextMessage(conn, message)
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        Log.d(TAG, "Received binary message, size: ${message.remaining()}")
        handleBinaryMessage(conn, message)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
        onError("WebSocket错误: ${ex.message}")
        
        conn?.let { socket ->
            clientSessions[socket]?.let { session ->
                session.fileOutputStream?.close()
                session.tempFile?.delete()
            }
            clientSessions.remove(socket)
        }
    }

    override fun onStart() {
        Log.d(TAG, "WebSocket server started on ${address}")
        onProgress("WebSocket服务器已启动: ${address}")
    }

    private fun handleTextMessage(conn: WebSocket, message: String) {
        val session = clientSessions[conn] ?: return
        
        when {
            message.startsWith(COMMAND_FILE_INFO) -> {
                handleFileInfo(conn, session, message)
            }
            message == COMMAND_TRANSFER_COMPLETE -> {
                handleTransferComplete(conn, session)
            }
            message.startsWith(COMMAND_ERROR) -> {
                val errorMsg = message.substringAfter(":")
                onError("客户端错误: $errorMsg")
            }
            else -> {
                Log.d(TAG, "Unknown command: $message")
                conn.send("{\"type\":\"error\",\"message\":\"Unknown command\"}")
            }
        }
    }

    private fun handleFileInfo(conn: WebSocket, session: ClientSession, message: String) {
        try {
            // Format: FILE_INFO:filename:filesize
            val parts = message.split(":")
            if (parts.size != 3) {
                conn.send("{\"type\":\"error\",\"message\":\"Invalid file info format\"}")
                return
            }
            
            val fileName = parts[1]
            val fileSize = parts[2].toLong()
            
            if (!fileName.endsWith(".apk", ignoreCase = true)) {
                conn.send("{\"type\":\"error\",\"message\":\"Only APK files are allowed\"}")
                return
            }
            
            // Create temp file
            val tempFile = File.createTempFile("update_", ".apk")
            val fileOutputStream = FileOutputStream(tempFile)
            
            session.fileName = fileName
            session.fileSize = fileSize
            session.receivedBytes = 0
            session.fileOutputStream = fileOutputStream
            session.tempFile = tempFile
            session.isReceivingFile = true
            
            onProgress("开始接收文件: $fileName (${formatFileSize(fileSize)})")
            conn.send("{\"type\":\"ready\",\"message\":\"Ready for data\"}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling file info", e)
            conn.send("{\"type\":\"error\",\"message\":\"Failed to prepare for file transfer\"}")
        }
    }

    private fun handleBinaryMessage(conn: WebSocket, message: ByteBuffer) {
        val session = clientSessions[conn] ?: return
        
        if (!session.isReceivingFile) {
            conn.send("{\"type\":\"error\",\"message\":\"Not expecting file data\"}")
            return
        }
        
        try {
            val data = ByteArray(message.remaining())
            message.get(data)
            
            session.fileOutputStream?.write(data)
            session.receivedBytes += data.size
            
            val progress = (session.receivedBytes * 100 / session.fileSize).toInt()
            onProgress("接收进度: ${formatFileSize(session.receivedBytes)}/${formatFileSize(session.fileSize)} ($progress%)")
            
            // Send progress acknowledgment in JSON format
            conn.send("{\"type\":\"progress\",\"progress\":$progress,\"received\":${session.receivedBytes},\"total\":${session.fileSize}}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file data", e)
            session.fileOutputStream?.close()
            session.tempFile?.delete()
            session.isReceivingFile = false
            conn.send("{\"type\":\"error\",\"message\":\"Failed to write file data\"}")
        }
    }

    private fun handleTransferComplete(conn: WebSocket, session: ClientSession) {
        try {
            Log.d(TAG, "handleTransferComplete called")
            session.fileOutputStream?.close()
            session.fileOutputStream = null
            
            val tempFile = session.tempFile
            Log.d(TAG, "Temp file: ${tempFile?.absolutePath}, exists: ${tempFile?.exists()}")
            Log.d(TAG, "Expected size: ${session.fileSize}, received: ${session.receivedBytes}")
            
            if (tempFile != null && tempFile.exists()) {
                if (session.receivedBytes == session.fileSize) {
                    Log.d(TAG, "File size matches, calling onApkReceived")
                    onProgress("文件接收完成: ${session.fileName}")
                    onApkReceived(tempFile)
                    conn.send("{\"type\":\"success\",\"message\":\"Transfer completed successfully\"}")
                } else {
                    Log.e(TAG, "文件大小不匹配: 期望${session.fileSize}, 实际${session.receivedBytes}")
                    onError("文件大小不匹配: 期望${session.fileSize}, 实际${session.receivedBytes}")
                    tempFile.delete()
                    conn.send("{\"type\":\"error\",\"message\":\"File size mismatch\"}")
                }
            } else {
                Log.e(TAG, "临时文件不存在或无法访问")
                onError("临时文件不存在")
                conn.send("{\"type\":\"error\",\"message\":\"Temp file not found\"}")
            }
            
            session.isReceivingFile = false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error completing transfer", e)
            session.tempFile?.delete()
            conn.send("{\"type\":\"error\",\"message\":\"Failed to complete transfer\"}")
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    fun broadcastMessage(message: String) {
        // 确保消息是JSON格式
        val jsonMessage = if (message.startsWith("{") && message.endsWith("}")) {
            message
        } else {
            "{\"type\":\"broadcast\",\"message\":\"$message\"}"
        }
        
        connections.forEach { conn ->
            conn.send(jsonMessage)
        }
    }

    fun getConnectedClientsCount(): Int = connections.size
}