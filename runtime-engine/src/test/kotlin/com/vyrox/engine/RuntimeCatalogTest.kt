package com.vyrox.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCatalogTest {
    @Test
    fun everyExternalEngineIsExplicitlyNotIntegrated() {
        assertTrue(RuntimeEngineCatalog.components.isNotEmpty())
        assertTrue(RuntimeEngineCatalog.components.all {
            it.integrationState == EngineIntegrationState.RESEARCH_ONLY ||
                it.integrationState == EngineIntegrationState.NOT_BUNDLED
        })
        assertEquals("MIT", RuntimeEngineCatalog.find("box64")?.license)
        assertEquals("MIT", RuntimeEngineCatalog.find("fex")?.license)
    }

    @Test
    fun vulkanParserRequiresAllDxvk27Capabilities() {
        val raw = listOf(
            "vulkan=available",
            "device=Test GPU",
            "api=1.3.0",
            "driver_version=123",
            "extensions=" + VulkanProbe.DXVK_27_REQUIRED_EXTENSIONS.joinToString(","),
            "max_push_constants=256",
            "descriptor_indexing=true"
        ).joinToString(";")

        val probe = VulkanProbeParser.parse(raw)

        assertTrue(probe.deviceAvailable)
        assertTrue(probe.supportsDxvk27Requirements)
        assertEquals("Test GPU", probe.deviceName)
    }

    @Test
    fun olderVulkanApiIsNotConsideredDxvk27Ready() {
        val raw = listOf(
            "vulkan=available",
            "api=1.2.0",
            "extensions=" + VulkanProbe.DXVK_27_REQUIRED_EXTENSIONS.joinToString(","),
            "max_push_constants=256",
            "descriptor_indexing=true"
        ).joinToString(";")

        assertFalse(VulkanProbeParser.parse(raw).supportsDxvk27Requirements)
    }

    @Test
    fun missingOneRequiredExtensionBlocksDxvk() {
        val extensions = VulkanProbe.DXVK_27_REQUIRED_EXTENSIONS - "VK_KHR_maintenance6"
        val raw = listOf(
            "vulkan=available",
            "api=1.3.0",
            "extensions=" + extensions.joinToString(","),
            "max_push_constants=256",
            "descriptor_indexing=true"
        ).joinToString(";")

        assertFalse(VulkanProbeParser.parse(raw).supportsDxvk27Requirements)
    }

    @Test
    fun windowsX64ReadinessListsEveryMissingRuntimeLayer() {
        val readiness = RuntimeCompatibilityPlanner.assessWindowsX64(
            X64RuntimePrerequisites(
                hostAbis = setOf("arm64-v8a"),
                linuxUserlandInstalled = false,
                x86_64RootFsInstalled = false,
                arm64WineInstalled = false,
                box64Installed = false,
                d3d11Requested = true,
                dxvkInstalled = false,
                vulkan = VulkanProbeParser.parse(null)
            )
        )

        assertFalse(readiness.canLaunch)
        assertTrue(readiness.blockers.any { it.contains("Linux userland") })
        assertTrue(readiness.blockers.any { it.contains("Wine") })
        assertTrue(readiness.blockers.any { it.contains("Box64") })
        assertTrue(readiness.blockers.any { it.contains("DXVK") })
    }

    @Test
    fun plannerAllowsLaunchOnlyWhenAllModeledPrerequisitesArePresent() {
        val vulkan = VulkanProbeParser.parse(
            listOf(
                "vulkan=available",
                "api=1.3.0",
                "extensions=" + VulkanProbe.DXVK_27_REQUIRED_EXTENSIONS.joinToString(","),
                "max_push_constants=256",
                "descriptor_indexing=true"
            ).joinToString(";")
        )
        val readiness = RuntimeCompatibilityPlanner.assessWindowsX64(
            X64RuntimePrerequisites(
                hostAbis = setOf("arm64-v8a"),
                linuxUserlandInstalled = true,
                x86_64RootFsInstalled = true,
                arm64WineInstalled = true,
                box64Installed = true,
                d3d11Requested = true,
                dxvkInstalled = true,
                vulkan = vulkan
            )
        )

        assertTrue(readiness.canLaunch)
        assertTrue(readiness.blockers.isEmpty())
    }
}
