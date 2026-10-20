package com.devicescope.app.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs

/** A point-in-time reading of public, on-device system information. */
data class DeviceDiagnosticSnapshot(
    val deviceName: String,
    val androidRelease: String,
    val apiLevel: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val freeStorageBytes: Long,
    val totalStorageBytes: Long,
    val capturedAtMillis: Long,
)

object DeviceDiagnosticReader {
    fun read(context: Context): DeviceDiagnosticSnapshot {
        val appContext = context.applicationContext
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val stats = StatFs(Environment.getDataDirectory().absolutePath)

        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val deviceName = listOf(manufacturer, model)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(separator = " ")
            .ifBlank { "Dispositivo Android" }

        return DeviceDiagnosticSnapshot(
            deviceName = deviceName,
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "No disponible" },
            apiLevel = Build.VERSION.SDK_INT,
            totalRamBytes = memoryInfo.totalMem,
            availableRamBytes = memoryInfo.availMem,
            freeStorageBytes = stats.availableBytes,
            totalStorageBytes = stats.totalBytes,
            capturedAtMillis = System.currentTimeMillis(),
        )
    }

    fun totalRamBytes(context: Context): Long {
        val activityManager = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo()
            .also(activityManager::getMemoryInfo)
            .totalMem
    }
}

fun isRamHealthy(snapshot: DeviceDiagnosticSnapshot): Boolean =
    availableRatio(snapshot.availableRamBytes, snapshot.totalRamBytes) >= 0.10

fun isStorageHealthy(snapshot: DeviceDiagnosticSnapshot): Boolean =
    availableRatio(snapshot.freeStorageBytes, snapshot.totalStorageBytes) >= 0.10

/** A simple local indicator; it is not a manufacturer or repair-grade diagnosis. */
fun calculateHealthScore(snapshot: DeviceDiagnosticSnapshot): Int {
    val ramRatio = availableRatio(snapshot.availableRamBytes, snapshot.totalRamBytes)
    val storageRatio = availableRatio(snapshot.freeStorageBytes, snapshot.totalStorageBytes)

    var score = 100
    score -= when {
        ramRatio < 0.05 -> 25
        ramRatio < 0.15 -> 10
        else -> 0
    }
    score -= when {
        storageRatio < 0.05 -> 25
        storageRatio < 0.15 -> 10
        else -> 0
    }
    return score.coerceIn(0, 100)
}

private fun availableRatio(available: Long, total: Long): Double =
    if (total <= 0L) 1.0 else (available.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
