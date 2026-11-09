package com.vyrox.runtime.core.host

/**
 * Optional JNI bridge for collecting native Android build information.
 * Failure to load the library is non-fatal; it does not block the Android UI.
 */
object NativeHostBridge {
    private val libraryAvailable: Boolean by lazy {
        runCatching { System.loadLibrary("vyrox_native") }.isSuccess
    }

    fun readBuildInfo(): String? {
        if (!libraryAvailable) return null
        return runCatching { nativeBuildInfo() }.getOrNull()
    }

    fun readVulkanInfo(): String? {
        if (!libraryAvailable) return null
        return runCatching { nativeVulkanInfo() }.getOrNull()
    }

    private external fun nativeBuildInfo(): String

    private external fun nativeVulkanInfo(): String
}
