package com.vyrox.runtime.core.host

import android.app.ActivityManager
import android.content.Context
import android.os.Build

data class HostCapabilities(
    val supportedAbis: List<String>,
    val supported64BitAbis: List<String>,
    val logicalCpuCount: Int,
    val totalMemoryBytes: Long,
    val nativeBuildInfo: String? = null
) {
    val supportsArm64RuntimeHost: Boolean
        get() = ARM64_ABI in supported64BitAbis

    companion object {
        const val ARM64_ABI = "arm64-v8a"
    }
}

/**
 * Reads host capabilities from Android itself. This does not load or execute
 * a Windows compatibility engine.
 */
object HostCapabilitiesProbe {
    fun read(context: Context): HostCapabilities {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        activityManager?.getMemoryInfo(memoryInfo)

        return HostCapabilities(
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS?.toList().orEmpty(),
            logicalCpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            totalMemoryBytes = memoryInfo.totalMem.coerceAtLeast(0L),
            nativeBuildInfo = NativeHostBridge.readBuildInfo()
        )
    }
}
