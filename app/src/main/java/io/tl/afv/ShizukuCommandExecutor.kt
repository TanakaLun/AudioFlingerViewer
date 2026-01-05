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
            // 使用反射获取 newProcess 方法
            newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess", 
                Array<String>::class.java, 
                Array<String>::class.java, 
                String::class.java
            )
            newProcessMethod?.isAccessible = true
        } catch (e: Exception) {
            Log.e("AudioFlingerDump", "无法获取 newProcess 方法", e)
        }
    }
    
    private fun createProcess(command: Array<String>, env: Array<String>?, dir: String?): ShizukuRemoteProcess? {
        return try {
            newProcessMethod?.invoke(null, command, env, dir) as? ShizukuRemoteProcess
        } catch (e: Exception) {
            Log.e("AudioFlingerDump", "创建进程失败", e)
            null
        }
    }
    
    private suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!Shizuku.pingBinder()) {
                return@withContext "Shizuku服务未运行"
            }
            
            Log.d("AudioFlingerDump", "使用Shizuku执行命令: $command")
            
            val process = createProcess(arrayOf("sh", "-c", command), null, "/")
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
            
            process.waitFor()
            result.toString()
            
        } catch (e: Exception) {
            Log.e("AudioFlingerDump", "使用Shizuku执行命令失败", e)
            "执行命令时出错: ${e.message}"
        }
    }
    
    suspend fun executeDumpsysCommand(): String {
        return executeCommand("dumpsys media.audio_flinger")
    }
    
    suspend fun executeAndParseAudioFlinger(): String {
        val rawOutput = executeCommand("dumpsys media.audio_flinger")
        return parseAudioFlingerOutput(rawOutput)
    }
    
    private fun parseAudioFlingerOutput(rawOutput: String): String {
        val result = StringBuilder()
        result.appendLine("=== 音频应用播放信息 ===")
        
        if (rawOutput.contains("Shizuku服务未运行") || 
            rawOutput.contains("无法创建Shizuku进程") || 
            rawOutput.contains("执行命令时出错")) {
            return rawOutput
        }
        
        // 解析客户端信息
        val clientMap = mutableMapOf<String, String>() // pid -> packageName
        
        // 解析Notification Clients部分
        val notificationClientsSection = rawOutput.lines().dropWhile { 
            !it.contains("Notification Clients:") 
        }.takeWhile { 
            it.isNotBlank() && !it.contains("Global session refs:") 
        }
        
        notificationClientsSection.forEach { line ->
            val trimmedLine = line.trim()
            // 匹配格式: " 30548  10553  com.salt.music"
            val clientPattern = """^\s*(\d+)\s+\d+\s+([\w\.]+)$""".toRegex()
            val match = clientPattern.find(trimmedLine)
            if (match != null) {
                val (pid, packageName) = match.destructured
                if (packageName.isNotBlank() && !packageName.startsWith("android.uid.")) {
                    clientMap[pid] = packageName
                    Log.d("AudioFlingerDump", "找到客户端: PID=$pid, 包名=$packageName")
                }
            }
        }
        
        // 解析活跃的音频轨道
        val activeTracks = mutableListOf<TrackInfo>()
        
        // 查找所有输出线程部分
        val threadSections = rawOutput.split("Output thread").drop(1)
        
        threadSections.forEach { threadSection ->
            // 检查线程是否处于活跃状态（非Standby）
            if (!threadSection.contains("Standby: yes")) {
                // 在线程中查找活跃轨道
                val lines = threadSection.lines()
                
                // 查找活跃轨道表头
                val tracksHeaderIndex = lines.indexOfFirst { 
                    it.contains("Tracks of which") && it.contains("are active") 
                }
                
                if (tracksHeaderIndex != -1 && tracksHeaderIndex + 1 < lines.size) {
                    // 查找表头下面的数据行
                    var dataLineIndex = tracksHeaderIndex + 1
                    
                    // 跳过列标题行（如果有）
                    if (lines[dataLineIndex].contains("Type") || lines[dataLineIndex].contains("Id")) {
                        dataLineIndex++
                    }
                    
                    // 解析数据行直到空行或下一个节
                    while (dataLineIndex < lines.size && 
                           lines[dataLineIndex].isNotBlank() && 
                           !lines[dataLineIndex].startsWith(" ") && 
                           !lines[dataLineIndex].contains("Effect Chains")) {
                        
                        val line = lines[dataLineIndex].trim()
                        
                        // 匹配活跃轨道行
                        val trackPattern = """^(\w+)\s+yes\s+(\d+)\s+\d+\s+\d+\s+\w\s+0x[0-9A-F]+\s+[0-9A-F]+\s+[0-9A-F]+\s+(\d+)""".toRegex()
                        val match = trackPattern.find(line)
                        
                        if (match != null) {
                            val (trackId, clientPid, sampleRate) = match.destructured
                            val packageName = clientMap[clientPid] ?: "未知应用(pid:$clientPid)"
                            
                            activeTracks.add(TrackInfo(
                                packageName = packageName,
                                trackId = trackId,
                                clientPid = clientPid,
                                sampleRate = sampleRate
                            ))
                            
                            Log.d("AudioFlingerDump", "找到活跃轨道: 包名=$packageName, PID=$clientPid, 采样率=$sampleRate Hz")
                        }
                        
                        dataLineIndex++
                    }
                }
            }
        }
        
        // 备选方案：如果上述方法没找到，尝试更宽松的搜索
        if (activeTracks.isEmpty()) {
            // 在整个输出中搜索活跃轨道模式
            val relaxedPattern = """(\w+)\s+yes\s+(\d+)\s+\d+\s+\d+\s+\w\s+0x[0-9A-F]+\s+[0-9A-F]+\s+[0-9A-F]+\s+(\d+)""".toRegex()
            val matches = relaxedPattern.findAll(rawOutput)
            
            matches.forEach { match ->
                val (trackId, clientPid, sampleRate) = match.destructured
                val packageName = clientMap[clientPid] ?: "未知应用(pid:$clientPid)"
                
                // 避免重复添加
                if (activeTracks.none { it.trackId == trackId && it.clientPid == clientPid }) {
                    activeTracks.add(TrackInfo(
                        packageName = packageName,
                        trackId = trackId,
                        clientPid = clientPid,
                        sampleRate = sampleRate
                    ))
                    Log.d("AudioFlingerDump", "通过宽松搜索找到轨道: 包名=$packageName, PID=$clientPid, 采样率=$sampleRate Hz")
                }
            }
        }
        
        if (activeTracks.isNotEmpty()) {
            result.appendLine("发现 ${activeTracks.size} 个活跃音频轨道:\n")
            
            activeTracks.forEach { track ->
                result.appendLine("应用包名: ${track.packageName}")
                result.appendLine("进程PID: ${track.clientPid}")
                result.appendLine("采样率: ${track.sampleRate} Hz")
                result.appendLine("---")
            }
            
            // 添加统计信息
            result.appendLine("=== 统计信息 ===")
            val sampleRates = activeTracks.map { it.sampleRate }.distinct().sorted()
            result.appendLine("使用的采样率: ${sampleRates.joinToString(", ")} Hz")
            
            val appStats = activeTracks.groupBy { it.packageName }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }
            
            result.appendLine("\n应用使用统计:")
            appStats.forEach { (app, count) ->
                result.appendLine("  $app: $count 个活跃轨道")
            }
        } else {
            result.appendLine("未找到活跃的音频轨道")
            result.appendLine("\n=== 完整原始输出 ===")
            result.appendLine(rawOutput)
        }
        
        return result.toString()
    }
    
    data class TrackInfo(
        val packageName: String,
        val trackId: String,
        val clientPid: String,
        val sampleRate: String
    )
}