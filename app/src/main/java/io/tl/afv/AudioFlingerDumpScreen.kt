package io.tl.afv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioFlingerDumpScreen() {
    var dumpResult by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var shizukuStatus by remember { mutableStateOf("检查Shizuku状态...") }
    val context = LocalContext.current
    
    val coroutineScope = rememberCoroutineScope()
    
    // 实时检查Shizuku状态
    LaunchedEffect(Unit) {
        // 初始状态检查
        shizukuStatus = getShizukuStatus()
        
        // 设置权限请求结果监听器
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            coroutineScope.launch {
                // 权限请求结果返回后立即更新状态
                shizukuStatus = getShizukuStatus()
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(context, "Shizuku授权成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Shizuku授权被拒绝", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 24.dp) // 为状态栏留出空间
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "音频输出信息监控",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            // Shizuku状态显示 - 现在可点击
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        shizukuStatus.contains("已授权") -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                ),
                onClick = {
                    // 当Shizuku未授权时，点击Card进行处理
                    if (!shizukuStatus.contains("已授权")) {
                        if (!Shizuku.pingBinder()) {
                            // Shizuku未运行，跳转至官网
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
                                context.startActivity(intent)
                                Toast.makeText(context, "请安装Shizuku后重试", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                            }
                        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            // Shizuku已运行但未授权，申请权限
                            requestShizukuPermission()
                            Toast.makeText(context, "请授权Shizuku权限", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Shizuku状态",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = shizukuStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    
                    // 当未授权时显示提示文本
                    if (!shizukuStatus.contains("已授权")) {
                        Text(
                            text = "点击此处处理Shizuku授权",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // 结果显示Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("正在获取音频应用信息...")
                        }
                    }
                } else {
                    val displayText = if (dumpResult.isNotEmpty()) {
                        dumpResult
                    } else {
                        "点击下方按钮获取音频应用信息"
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        item {
                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            // 操作按钮
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 主功能按钮 - 获取并解析音频信息
                Button(
                    onClick = {
                        if (checkShizukuPermission()) {
                            isLoading = true
                            dumpResult = ""
                            
                            coroutineScope.launch {
                                dumpResult = ShizukuCommandExecutor.executeAndParseAudioFlinger()
                                isLoading = false
                            }
                        } else {
                            // 如果未授权，显示提示
                            Toast.makeText(
                                context, 
                                "请先完成Shizuku授权（点击上方状态卡片）", 
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("获取音频应用信息")
                    }
                }
            }
        }
    }
}

// Shizuku权限检查
fun checkShizukuPermission(): Boolean {
    return try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }
}

// 请求Shizuku权限
fun requestShizukuPermission() {
    try {
        Shizuku.requestPermission(1000)
    } catch (e: Exception) {
        // 忽略错误
    }
}

// 获取Shizuku状态
fun getShizukuStatus(): String {
    return try {
        when {
            !Shizuku.pingBinder() -> "Shizuku未运行 - 点击安装"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    "Shizuku已运行 - 需要授权 (用户选择了拒绝) - 点击重新授权"
                } else {
                    "Shizuku已运行 - 需要授权 - 点击授权"
                }
            }
            else -> {
                val uid = Shizuku.getUid()
                val privilege = if (uid == 0) "Root" else if (uid == 2000) "ADB" else "Unknown"
                "Shizuku已运行 - 已授权 ($privilege)"
            }
        }
    } catch (e: Exception) {
        "Shizuku状态检查失败: ${e.message} - 点击处理"
    }
}