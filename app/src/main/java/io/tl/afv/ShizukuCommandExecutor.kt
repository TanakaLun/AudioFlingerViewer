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
        
        val lines = rawOutput.lines()
        val clientMap = mutableMapOf<String, String>() // pid -> packageName
        
        // 解析 Notification Clients 部分
        var i = 0
        while (i < lines.size && !lines[i].contains("Notification Clients:")) i++
        i++ // 跳过标题行
        if (i < lines.size && lines[i].trim().startsWith("pid")) i++ // 跳过列名行
        
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isBlank() || line.contains("Global session refs:")) break
            
            // 匹配格式: "24000  10553  package"
            val clientPattern = """^(\d+)\s+\d+\s+([\w\.]+)$""".toRegex()
            val match = clientPattern.find(line)
            if (match != null) {
                val (pid, packageName) = match.destructured
                // 过滤掉系统 uid 条目
                if (packageName.isNotBlank() && !packageName.startsWith("android.uid.")) {
                    clientMap[pid] = packageName
                    Log.d("AudioFlingerDump", "找到客户端: PID=$pid, 包名=$packageName")
                }
            }
            i++
        }
        
        val activeTracks = mutableListOf<TrackInfo>()
        
        // 逐线程扫描
        i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("Output thread")) {
                val threadLines = mutableListOf<String>()
                var j = i
                while (j < lines.size && (!lines[j].startsWith("Output thread") || j == i)) {
                    threadLines.add(lines[j])
                    j++
                }
                i = j
                parseThreadBlock(threadLines, clientMap, activeTracks)
            } else {
                i++
            }
        }
        
        // 备选：宽松搜索（当上述解析失效时）
        if (activeTracks.isEmpty()) {
            Log.d("AudioFlingerDump", "使用宽松搜索")
            val relaxedPattern = """(\S+)\s+yes\s+(\d+)/\s*\d+\s+\d+\s+\d+\s+\S+\s+0x[0-9A-F]+\s+[0-9A-F]+\s+[0-9A-F]+\s+(\d+)""".toRegex()
            val matches = relaxedPattern.findAll(rawOutput)
            matches.forEach { match ->
                val (trackId, clientPid, sampleRate) = match.destructured
                val packageName = clientMap[clientPid] ?: "未知应用(pid:$clientPid)"
                // 避免重复添加（基于 trackId + pid）
                if (activeTracks.none { it.trackId == trackId && it.clientPid == clientPid }) {
                    activeTracks.add(TrackInfo(
                        packageName = packageName,
                        trackId = trackId,
                        clientPid = clientPid,
                        sampleRate = sampleRate
                    ))
                    Log.d("AudioFlingerDump", "宽松搜索找到轨道: 包名=$packageName, PID=$clientPid, 采样率=$sampleRate Hz")
                }
            }
        }
        
        // 构建输出
        if (activeTracks.isNotEmpty()) {
            result.appendLine("发现 ${activeTracks.size} 个活跃音频轨道:\n")
            activeTracks.forEach { track ->
                result.appendLine("应用包名: ${track.packageName}")
                result.appendLine("进程PID: ${track.clientPid}")
                result.appendLine("采样率: ${track.sampleRate} Hz")
                result.appendLine("---")
            }
            
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
    
    private fun parseThreadBlock(
        threadLines: List<String>,
        clientMap: Map<String, String>,
        activeTracks: MutableList<TrackInfo>
    ) {
        // 检查 Standby 状态
        val standbyLine = threadLines.find { it.contains("Standby:") }
        if (standbyLine?.contains("Standby: yes") == true) {
            return // 线程未活跃
        }
        
        // 查找 "Tracks of which X are active" 行
        val tracksHeaderIndex = threadLines.indexOfFirst { it.contains("Tracks of which") && it.contains("are active") }
        if (tracksHeaderIndex == -1) return
        
        var dataIndex = tracksHeaderIndex + 1
        // 跳过可能的列标题行
        if (dataIndex < threadLines.size && 
            (threadLines[dataIndex].contains("Type") || threadLines[dataIndex].contains("Id"))) {
            dataIndex++
        }
        
        // 正则：提取 pid 和采样率
        val trackPattern = """^\s*\S+\s+yes\s+(\d+)/\s*\d+\s+\d+\s+\d+\s+\S+\s+0x[0-9A-F]+\s+[0-9A-F]+\s+[0-9A-F]+\s+(\d+)""".toRegex()
        
        while (dataIndex < threadLines.size) {
            val line = threadLines[dataIndex]
            if (line.isBlank() || line.contains("Effect Chains") || line.startsWith("Local log:")) {
                break
            }
            
            if (line.contains("yes")) {
                val match = trackPattern.find(line)
                if (match != null) {
                    val (clientPid, sampleRate) = match.destructured
                    val packageName = clientMap[clientPid] ?: "未知应用(pid:$clientPid)"
                    
                    // 提取 trackId (可选)
                    val trackIdMatch = """^\s*(\S+)\s+yes""".toRegex().find(line)
                    val trackId = trackIdMatch?.groupValues?.get(1) ?: "未知"
                    
                    activeTracks.add(TrackInfo(
                        packageName = packageName,
                        trackId = trackId,
                        clientPid = clientPid,
                        sampleRate = sampleRate
                    ))
                    
                    Log.d("AudioFlingerDump", "找到活跃轨道: 包名=$packageName, PID=$clientPid, 采样率=$sampleRate Hz")
                }
            }
            dataIndex++
        }
    }
    
    data class TrackInfo(
        val packageName: String,
        val trackId: String,
        val clientPid: String,
        val sampleRate: String
    )
}