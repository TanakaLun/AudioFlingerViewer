package io.tl.afv

data class AudioTrackInfo(
    // 应用信息
    val packageName: String,
    val pid: String,
    val uid: String,
    val sessionId: String,
    
    // 请求规格 (应用请求的参数)
    val requestedSampleRate: String,
    val requestedChannelMask: String,
    val requestedFormat: String,
    
    // 播放规格 (实际输出的参数)
    val actualSampleRate: String,
    val actualChannelMask: String,
    val actualFormat: String,
    val actualChannelCount: String,
    
    // 线程信息
    val threadName: String,
    val threadType: String,
    val outputDevice: String,
    
    // 状态
    val isActive: Boolean,
    val flags: String,
    val latency: String
    
    // 触觉
    val hapticChannelMask: String = "0x0",
    val isHapticActive: Boolean = false
) {
    // 暂未知是否可行，仅占位
    fun getChannelMaskDescription(mask: String): String {
        return when (mask.trim()) {
            "0x00000003", "0x3" -> "立体声 (Stereo)"
            "0x00000001", "0x1" -> "单声道 (Mono)"
            "0x0000000b", "0xb" -> "2.1声道"
            "0x00000033", "0x33" -> "3.0声道"
            "0x00000037", "0x37" -> "3.1声道"
            "0x0000003f", "0x3f" -> "5.1声道"
            "0x0000013f", "0x13f" -> "7.1声道"
            "0x20000003" -> "立体声+触觉 (Stereo+Haptic)"
            else -> mask
        }
    }
    
    fun getFormatDescription(format: String): String {
        return when (format.trim()) {
            "0x1", "00000001" -> "PCM 16位"
            "0x3", "00000003" -> "PCM 32位"
            "0x4", "00000004" -> "PCM 8.24位"
            "0x5", "00000005" -> "PCM 浮点 (Float)"
            "0x6", "00000006" -> "PCM 24位打包"
            else -> format
        }
    }
    
    fun getThreadTypeDescription(type: String): String {
        return when (type) {
            "1" -> "混音器 (MIXER)"
            "2" -> "直通 (DIRECT)"
            "4" -> "硬解 (OFFLOAD)"
            else -> "其他 ($type)"
        }
    }
    
    fun getDeviceDescription(device: String): String {
        return when {
            device.contains("0x2") -> "扬声器 (Speaker)"
            device.contains("0x1") -> "耳机 (Headphone)"
            device.contains("0x4") -> "蓝牙A2DP"
            device.contains("0x8") -> "蓝牙SCO"
            device.contains("0x10000") -> "通话上行 (Telephony TX)"
            else -> device
        }
    }
    
    fun getPlaybackSummary(): String {
        return """
            应用: $packageName (PID: $pid)
            请求规格: ${getFormatDescription(requestedFormat)} @ ${requestedSampleRate}Hz - ${getChannelMaskDescription(requestedChannelMask)}
            实际输出: ${getFormatDescription(actualFormat)} @ ${actualSampleRate}Hz - ${getChannelMaskDescription(actualChannelMask)} (${actualChannelCount}通道)
            输出设备: ${getDeviceDescription(outputDevice)}
            线程: $threadName (${getThreadTypeDescription(threadType)})
        """.trimIndent()
    }
}