package io.tl.afv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import io.tl.afv.ui.theme.MyComposeApplicationTheme

class MainActivity : ComponentActivity() {
    
    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        // 权限请求结果处理
        when (grantResult) {
            PackageManager.PERMISSION_GRANTED -> {
                // 权限已授予，可以执行相关操作
            }
            PackageManager.PERMISSION_DENIED -> {
                // 权限被拒绝
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置全屏UI
        setupFullScreenUI()
        
        // 初始化Shizuku监听器
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        
        setContent {
            MyComposeApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AudioFlingerDumpScreen()
                }
            }
        }
    }

    /**
     * 设置全屏UI，隐藏状态栏
     */
    private fun setupFullScreenUI() {
        // 启用边到边
        enableEdgeToEdge()
        
        // 隐藏状态栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // 隐藏状态栏和导航栏
        window.insetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
    }

    override fun onResume() {
        super.onResume()
        // 确保在Activity恢复时保持全屏状态
        setupFullScreenUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 当窗口获得焦点时，保持状态栏和导航栏隐藏
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 移除Shizuku监听器
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }
}