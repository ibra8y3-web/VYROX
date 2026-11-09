package com.vyrox.runtime.core.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCapabilitiesTest {
    @Test
    fun recognizesArm64AsTheInitialRuntimeHost() {
        val profile = HostCapabilities(
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            supported64BitAbis = listOf("arm64-v8a"),
            logicalCpuCount = 8,
            totalMemoryBytes = 6L * 1024L * 1024L * 1024L
        )

        assertTrue(profile.supportsArm64RuntimeHost)
    }

    @Test
    fun doesNotTreatX86AsArm64() {
        val profile = HostCapabilities(
            supportedAbis = listOf("x86_64", "x86"),
            supported64BitAbis = listOf("x86_64"),
            logicalCpuCount = 4,
            totalMemoryBytes = 4L * 1024L * 1024L * 1024L
        )

        assertFalse(profile.supportsArm64RuntimeHost)
    }
}
