package com.tableos.testapp

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val textView = TextView(this).apply {
            text = "测试应用安装成功！\n\n" +
                   "版本: 1.0\n" +
                   "安装时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n\n" +
                   "这是一个用于测试Super Update功能的简单应用。"
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        
        setContentView(textView)
    }
}