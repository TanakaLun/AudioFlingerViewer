package io.tl.afv

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

object ShizukuCommandExecutor {
    
    private const val TAG = "ShizukuExecutor"
    private var newProcessMethod: Method? = null
    
    init {
        try {
            newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess", 
                Array<String>::class.java, 
                Array<String>::class.java, 
                String::class.java
            )
            newProcessMethod?.isAccessible = true
        } catch (e: Exception) {
            Log.e(TAG, "无法获取 newProcess 方法", e)
        }
    }
    
    private fun createProcess(command: Array<String>, env: Array<String>?, dir: String?): ShizukuRemoteProcess? {
        return try {
            newProcessMethod?.invoke(null, command, env, dir) as? ShizukuRemoteProcess
        } catch (e: Exception) {
            Log.e(TAG, "创建进程失败", e)
            null
        }
    }
    
    suspend fun executeDumpsysCommand(): String = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!Shizuku.pingBinder()) {
                return@withContext "Shizuku服务未运行"
            }
            
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return@withContext "Shizuku权限未授予"
            }
            
            Log.d(TAG, "使用Shizuku执行命令: dumpsys media.audio_flinger")
            
            val process = createProcess(arrayOf("sh", "-c", "dumpsys media.audio_flinger"), null, "/")
            if (process == null) {
                return@withContext "无法创建Shizuku进程"
            }
            
            val result = StringBuilder()
            
            // 读取标准输出
            BufferedReader(InputStreamReader(process.inputStream)).use { input ->
                var line: String?
                while (input.readLine().also { line = it } != null) {
                    result.appendLine(line)
                }
            }
            
            // 读取错误输出
            BufferedReader(InputStreamReader(process.errorStream)).use { error ->
                var line: String?
                while (error.readLine().also { line = it } != null) {
                    result.appendLine("ERROR: $line")
                }
            }
            
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.w(TAG, "命令执行退出码: $exitCode")
            }
            
            result.toString()
            
        } catch (e: Exception) {
            Log.e(TAG, "使用Shizuku执行命令失败", e)
            "执行命令时出错: ${e.message}"
        }
    }
    
    suspend fun executeAndParseAudioFlinger(): String = withContext(Dispatchers.IO) {
        val rawOutput = executeDumpsysCommand()
        
        if (rawOutput.startsWith("Shizuku服务未运行") || 
            rawOutput.startsWith("Shizuku权限未授予") || 
            rawOutput.startsWith("执行命令时出错")) {
            return@withContext rawOutput
        }
        
        val parseResult = AudioFlingerParser.parse(rawOutput)
        formatOutput(parseResult)
    }
    
    private fun formatOutput(result: AudioFlingerParser.ParseResult): String {
        val output = StringBuilder()
        
        if (result.error != null) {
            output.appendLine("⚠️ 解析过程中出现错误")
            output.appendLine(result.error)
            return output.toString()
        }
        
        if (result.tracks.isEmpty()) {
            output.appendLine("📢 当前没有活跃的音频轨道")
            output.appendLine("\n可能的原因:")
            output.appendLine("• 没有应用在播放音频")
            output.appendLine("• 音频系统处于待机状态")
            output.appendLine("• 解析器需要调整以匹配系统输出格式")
            
            // 添加原始输出的前几行用于调试
            output.appendLine("\n=== 调试信息 ===")
            result.rawOutput.lines().take(20).forEach { line ->
                if (line.isNotBlank()) {
                    output.appendLine(line.take(200))
                }
            }
            
            return output.toString()
        }
        
        output.appendLine("🎵 当前活跃音频轨道 (${result.tracks.size})\n")
        
        result.tracks.forEachIndexed { index, track ->
            output.appendLine("【轨道 ${index + 1}】")
            output.appendLine("📱 应用: ${track.packageName}")
            output.appendLine("🆔 PID: ${track.pid} | UID: ${track.uid} | Session: ${track.sessionId}")
            output.appendLine("")
            
            output.appendLine("   📝 请求规格 (应用请求):")
            output.appendLine("   • 采样率: ${track.requestedSampleRate} Hz")
            output.appendLine("   • 格式: ${track.getFormatDescription(track.requestedFormat)}")
            output.appendLine("   • 通道: ${track.getChannelMaskDescription(track.requestedChannelMask)}")
            output.appendLine("")
            
            output.appendLine("   🔊 播放规格 (实际输出):")
            output.appendLine("   • 采样率: ${track.actualSampleRate} Hz")
            output.appendLine("   • 格式: ${track.getFormatDescription(track.actualFormat)}")
            output.appendLine("   • 通道: ${track.getChannelMaskDescription(track.actualChannelMask)} (${track.actualChannelCount}通道)")
            output.appendLine("")
            
            output.appendLine("   ⚙️ 输出信息:")
            output.appendLine("   • 线程: ${track.threadName}")
            output.appendLine("   • 类型: ${track.getThreadTypeDescription(track.threadType)}")
            output.appendLine("   • 设备: ${track.getDeviceDescription(track.outputDevice)}")
            
            if (index < result.tracks.size - 1) {
                output.appendLine("\n" + "=".repeat(50) + "\n")
            }
        }
        
        // 添加统计信息
        output.appendLine("\n📊 统计信息")
        output.appendLine("-".repeat(30))
        
        val apps = result.tracks.groupBy { it.packageName }
        output.appendLine("活跃应用数: ${apps.size}")
        apps.forEach { (app, tracks) ->
            output.appendLine("  • $app: ${tracks.size} 个轨道")
        }
        
        val sampleRates = result.tracks.map { it.actualSampleRate }.distinct().sorted()
        output.appendLine("\n使用的采样率: ${sampleRates.joinToString(", ")} Hz")
        
        val formats = result.tracks.map { track.getFormatDescription(track.actualFormat) }.distinct()
        output.appendLine("使用的格式: ${formats.joinToString(", ")}")
        
        return output.toString()
    }
}