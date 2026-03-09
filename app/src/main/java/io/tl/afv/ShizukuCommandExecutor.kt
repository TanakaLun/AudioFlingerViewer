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

    private var newProcessMethod: Method? = null

    init {
        try {
            newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (e: Exception) {
            Log.e(TAG, "无法初始化 Shizuku 反射方法", e)
        }
    }

    private fun createProcess(command: Array<String>): ShizukuRemoteProcess? {
        return try {
            newProcessMethod?.invoke(null, command, null, null) as? ShizukuRemoteProcess
        } catch (e: Exception) {
            Log.e(TAG, "创建进程失败", e)
            null
        }
    }

    suspend fun getAudioFlingerDump(): String = withContext(Dispatchers.IO) {
        val rawOutput = executeCommand("dumpsys media.audio_flinger")
        if (rawOutput.isBlank() || rawOutput.startsWith("Error")) return@withContext rawOutput
        
        return@withContext parseAudioFlinger(rawOutput)
    }

    private fun executeCommand(command: String): String {
        val process = createProcess(command.split(" ").toTypedArray()) ?: return "Error: 无法启动 Shizuku 进程"
        return try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
            output.toString()
        } catch (e: Exception) {
            "Error: 执行命令失败 - ${e.message}"
        } finally {
            process.destroy()
        }
    }

    private fun parseAudioFlinger(rawOutput: String): String {
        val result = StringBuilder()
        val pkgMap = mutableMapOf<String, String>()
        val activeTracks = mutableListOf<TrackInfo>()
        
        val lines = rawOutput.lines()
        var inNotificationClients = false
        var inTracksSection = false

        lines.forEach { line ->
            val trimmed = line.trim()

            if (trimmed.startsWith("Notification Clients:")) {
                inNotificationClients = true
                return@forEach
            }
            if (inNotificationClients) {
                if (trimmed.isEmpty() || trimmed.startsWith("Global")) {
                    inNotificationClients = false
                } else {
                    // 格式: pid uid name
                    val parts = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (parts.size >= 3) {
                        pkgMap[parts[0]] = parts[2]
                    }
                }
            }

            // 2. 定位 Tracks 列表并提取活跃音轨
            if (trimmed.contains("Tracks of which")) {
                inTracksSection = true
                return@forEach
            }
            
            if (inTracksSection) {
                if (trimmed.isEmpty()) {
                    inTracksSection = false
                } else if (trimmed.contains("yes")) {
                    val parts = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
                    val clientInfo = parts.find { it.contains("/") }
                    if (clientInfo != null) {
                        val pid = clientInfo.split("/")[0]
                        val packageName = pkgMap[pid] ?: "未知应用($pid)"
                        
                        val sampleRate = parts.getOrNull(parts.indexOfFirst { it.contains(Regex("\\d{5}")) }) ?: "未知"
                        
                        activeTracks.add(TrackInfo(packageName, pid, sampleRate))
                    }
                }
            }
        }

        if (activeTracks.isNotEmpty()) {
            result.appendLine("🎵 发现 ${activeTracks.size} 个活跃音频来源:\n")
            activeTracks.forEach { track ->
                result.appendLine("📦 应用: ${track.packageName}")
                result.appendLine("🆔 进程 PID: ${track.clientPid}")
                result.appendLine("🔊 采样率: ${track.sampleRate} Hz")
                result.appendLine("-------------------------")
            }
        } else {
            result.appendLine("⚠️ 当前无活跃音频播放")
        }

        return result.toString()
    }

    data class TrackInfo(
        val packageName: String,
        val clientPid: String,
        val sampleRate: String
    )
}
