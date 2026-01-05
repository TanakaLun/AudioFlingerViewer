package io.tl.afv

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object ShizukuManager {
    
    private var permissionResultListener: Shizuku.OnRequestPermissionResultListener? = null
    
    fun checkPermission(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
    
    fun requestPermission() {
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "请求Shizuku权限失败", e)
        }
    }
    
    suspend fun getShizukuStatus(): String = withContext(Dispatchers.IO) {
        return@withContext try {
            when {
                !Shizuku.pingBinder() -> "Shizuku未运行"
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                    if (Shizuku.shouldShowRequestPermissionRationale()) {
                        "Shizuku已运行 - 需要授权 (用户选择了拒绝)"
                    } else {
                        "Shizuku已运行 - 需要授权"
                    }
                }
                else -> {
                    val uid = Shizuku.getUid()
                    val privilege = if (uid == 0) "Root" else if (uid == 2000) "ADB" else "Unknown"
                    "Shizuku已运行 - 已授权 ($privilege)"
                }
            }
        } catch (e: Exception) {
            "Shizuku状态检查失败: ${e.message}"
        }
    }
    
    fun setupPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        permissionResultListener = listener
        Shizuku.addRequestPermissionResultListener(listener)
    }
    
    fun removePermissionListener() {
        permissionResultListener?.let {
            Shizuku.removeRequestPermissionResultListener(it)
            permissionResultListener = null
        }
    }
}