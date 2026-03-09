package io.tl.afv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
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
    var lastUpdateTime by remember { mutableStateOf("") }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // 实时检查Shizuku状态
    LaunchedEffect(Unit) {
        shizukuStatus = getShizukuStatus()
        
        // 设置权限请求结果监听器
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            coroutineScope.launch {
                shizukuStatus = getShizukuStatus()
                val message = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    "Shizuku授权成功"
                } else {
                    "Shizuku授权被拒绝"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
                .padding(top = 24.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 标题
            Text(
                text = "音频输出信息监控",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            // Shizuku状态显示
            ShizukuStatusCard(
                status = shizukuStatus,
                onClick = {
                    if (!shizukuStatus.contains("已授权")) {
                        handleShizukuAction(context)
                    }
                }
            )

            // 上次更新时间
            if (lastUpdateTime.isNotEmpty()) {
                Text(
                    text = "上次更新: $lastUpdateTime",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.End)
                )
            }

            // 结果显示区域
            ResultCard(
                isLoading = isLoading,
                dumpResult = dumpResult
            )

            // 操作按钮
            ActionButtons(
                isLoading = isLoading,
                shizukuStatus = shizukuStatus,
                onRefresh = {
                    coroutineScope.launch {
                        isLoading = true
                        dumpResult = ""
                        
                        val result = ShizukuCommandExecutor.executeAndParseAudioFlinger()
                        dumpResult = result
                        
                        lastUpdateTime = java.text.SimpleDateFormat(
                            "HH:mm:ss", 
                            java.util.Locale.getDefault()
                        ).format(java.util.Date())
                        
                        isLoading = false
                    }
                }
            )
        }
    }
}

@Composable
fun ShizukuStatusCard(
    status: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                status.contains("已授权") -> MaterialTheme.colorScheme.primaryContainer
                status.contains("未运行") -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.tertiaryContainer
            }
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Shizuku状态",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            
            if (!status.contains("已授权")) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "需要处理",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun ResultCard(
    isLoading: Boolean,
    dumpResult: String
) {
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
                    Text("正在获取音频信息...")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                item {
                    Text(
                        text = if (dumpResult.isNotEmpty()) dumpResult else "点击下方按钮获取音频应用信息",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ActionButtons(
    isLoading: Boolean,
    shizukuStatus: String,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                if (checkShizukuPermission()) {
                    onRefresh()
                } else {
                    Toast.makeText(
                        context,
                        "请先完成Shizuku授权",
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
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("获取音频应用信息")
            }
        }
        
        // 当没有活跃轨道时显示提示
        if (!isLoading && shizukuStatus.contains("已授权") && !dumpResult.contains("活跃音频轨道")) {
            AssistChip(
                onClick = { 
                    Toast.makeText(
                        context,
                        "确保有应用正在播放音频",
                        Toast.LENGTH_LONG
                    ).show()
                },
                label = { Text("提示: 确保有应用正在播放音频") },
                modifier = Modifier.fillMaxWidth()
            )
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
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
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
                    "Shizuku已运行 - 需要授权 (用户选择了拒绝)"
                } else {
                    "Shizuku已运行 - 需要授权"
                }
            }
            else -> {
                val uid = Shizuku.getUid()
                val privilege = when (uid) {
                    0 -> "Root"
                    2000 -> "ADB"
                    else -> "Unknown"
                }
                "Shizuku已运行 - 已授权 ($privilege)"
            }
        }
    } catch (e: Exception) {
        "Shizuku状态检查失败: ${e.message}"
    }
}

// 处理Shizuku操作
fun handleShizukuAction(context: android.content.Context) {
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
        Toast.makeText(context, "请在弹窗中授权Shizuku权限", Toast.LENGTH_SHORT).show()
    }
}