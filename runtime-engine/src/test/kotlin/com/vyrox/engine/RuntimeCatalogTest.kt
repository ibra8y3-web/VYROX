package com.vyrox.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCatalogTest {
    private fun completeVulkan(
        api: String = "1.4.0",
        extensions: Set<String> = VulkanProbe.DXVK_3X_REQUIRED_EXTENSIONS
    ): VulkanProbe {
        val raw = listOf(
            "vulkan=available",
            "device=Test GPU",
            "api=$api",
            "driver_version=123",
            "extensions=" + extensions.joinToString(","),
            "max_push_constants=256",
            "descriptor_indexing=true",
            "scalar_block_layout=true",
            "shader_int8=true",
            "shader_int16=true",
            "shader_int64=true"
        ).joinToString(";")
        return VulkanProbeParser.parse(raw)
    }

    @Test
    fun everyExternalEngineIsExplicitlyNotIntegrated() {
        assertTrue(RuntimeEngineCatalog.components.isNotEmpty())
        assertTrue(RuntimeEngineCatalog.components.all {
            it.integrationState == EngineIntegrationState.RESEARCH_ONLY ||
                it.integrationState == EngineIntegrationState.NOT_BUNDLED
        })
        assertEquals("MIT", RuntimeEngineCatalog.find("box64")?.license)
        assertEquals("MIT", RuntimeEngineCatalog.find("fex")?.license)
        assertEquals("GPL-2.0-or-later", RuntimeEngineCatalog.find("proot")?.license)
    }

    @Test
    fun dxvk3RequiresVulkan14AndAllExtensionsAndFeatures() {
        val probe = completeVulkan()
        assertTrue(probe.deviceAvailable)
        assertTrue(probe.supportsDxvk3Requirements)
        assertEquals("Test GPU", probe.deviceName)
    }

    @Test
    fun vulkan13DoesNotPassDxvk3Preflight() {
        assertFalse(completeVulkan(api = "1.3.0").supportsDxvk3Requirements)
    }

    @Test
    fun missingOneRequiredExtensionBlocksDxvk3() {
        val missing = VulkanProbe.DXVK_3X_REQUIRED_EXTENSIONS - "VK_KHR_load_store_op_none"
        assertFalse(completeVulkan(extensions = missing).supportsDxvk3Requirements)
    }

    @Test
    fun missingScalarBlockLayoutBlocksDxvk3() {
        val probe = VulkanProbeParser.parse(
            listOf(
                "vulkan=available",
                "api=1.4.0",
                "extensions=" + VulkanProbe.DXVK_3X_REQUIRED_EXTENSIONS.joinToString(","),
                "max_push_constants=256",
                "descriptor_indexing=true",
                "scalar_block_layout=false",
                "shader_int8=true",
                "shader_int16=true",
                "shader_int64=true"
            ).joinToString(";")
        )
        assertFalse(probe.supportsDxvk3Requirements)
    }

    @Test
    fun lowPushConstantLimitBlocksDxvk3() {
        val probe = VulkanProbeParser.parse(
            listOf(
                "vulkan=available",
                "api=1.4.0",
                "extensions=" + VulkanProbe.DXVK_3X_REQUIRED_EXTENSIONS.joinToString(","),
                "max_push_constants=128",
                "descriptor_indexing=true",
                "scalar_block_layout=true",
                "shader_int8=true",
                "shader_int16=true",
                "shader_int64=true"
            ).joinToString(";")
        )
        assertFalse(probe.supportsDxvk3Requirements)
    }

    @Test
    fun windowsX64PreflightListsMissingRuntimeLayersAndExecutionProof() {
        val readiness = RuntimeCompatibilityPlanner.assessWindowsX64(
            X64RuntimePrerequisites(
                hostAbis = setOf("arm64-v8a"),
                linuxUserlandInstalled = false,
                x86_64RootFsInstalled = false,
                arm64WineInstalled = false,
                box64Installed = false,
                prootInstalled = false,
                d3d11Requested = true,
                dxvkInstalled = false,
                vulkan = VulkanProbeParser.parse(null)
            )
        )
        assertFalse(readiness.preflightPassed)
        assertTrue(readiness.blockers.any { it.contains("Linux userland") })
        assertTrue(readiness.blockers.any { it.contains("Wine") })
        assertTrue(readiness.blockers.any { it.contains("Box64") })
        assertTrue(readiness.blockers.any { it.contains("PRoot") })
        assertTrue(readiness.blockers.any { it.contains("DXVK") })
        assertTrue(readiness.blockers.any { it.contains("PE loader") })
        assertTrue(readiness.blockers.any { it.contains("physical-device") })
    }

    @Test
    fun modeledPreflightRequiresAllRuntimeAndExecutionInputs() {
        val readiness = RuntimeCompatibilityPlanner.assessWindowsX64(
            X64RuntimePrerequisites(
                hostAbis = setOf("arm64-v8a"),
                linuxUserlandInstalled = true,
                x86_64RootFsInstalled = true,
                arm64WineInstalled = true,
                box64Installed = true,
                prootInstalled = true,
                d3d11Requested = true,
                dxvkInstalled = true,
                vulkan = completeVulkan(),
                windowsPeLoaderInstalled = true,
                capsuleDriveMappingInstalled = true,
                physicalDeviceExecutionVerified = true
            )
        )
        assertTrue(readiness.preflightPassed)
        assertTrue(readiness.blockers.isEmpty())
    }
}
