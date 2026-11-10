package com.vyrox.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WineRuntimePlanTest {
    @Test
    fun installScriptUsesWineHqAndFindsTheRealLinuxLoaderAndWindowsBootPayload() {
        val script = WineRuntimePlan.createInstallShellScript()
        assertTrue(script.contains(WineRuntimePlan.WINEHQ_KEY_URL))
        assertTrue(script.contains("Architectures: amd64"))
        assertTrue(script.contains("winehq-stable"))
        assertTrue(script.contains("name wine64"))
        assertTrue(script.contains("x86_64-windows/wineboot.exe"))
        assertTrue(script.contains("xvfb"))
        assertTrue(script.contains("x11vnc"))
        assertTrue(script.contains("icoutils"))
        assertTrue(script.contains("VYROX_WINE_READY"))
        assertTrue(script.contains("vulkaninfo"))
        assertTrue(script.contains("BOX64"))
        assertTrue(script.contains("vulkan_backend="))
    }

    @Test
    fun prefixScriptExecutesWindowsWinebootThroughTheLinuxWineLoader() {
        val root = wineRoot()
        try {
            val script = WineRuntimePlan.createPrefixShellScript(
                "/opt/vyrox/prefixes/123e4567-e89b-12d3-a456-426614174000",
                root
            )
            assertTrue(script.contains("/usr/lib/wine/wine64"))
            assertTrue(script.contains("/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe"))
            assertTrue(script.contains("VYROX_WINEBOOT_OK"))
            assertTrue(script.contains(" -u"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interactiveRunRejectsFilesOutsideCapsuleStorage() {
        val root = wineRoot()
        try {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                WineRuntimePlan.createInteractiveRunScript(
                    "/opt/vyrox/prefixes/123e4567-e89b-12d3-a456-426614174000",
                    "/tmp/evil.exe",
                    WindowsInstallerKind.EXE,
                    root
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exeAndMsiCommandsStartTheVirtualDesktopAndUseDifferentInstallCommands() {
        val root = wineRoot()
        try {
            val exe = WineRuntimePlan.createInteractiveRunScript(
                "/opt/vyrox/prefixes/123e4567-e89b-12d3-a456-426614174000",
                "/mnt/vyrox/capsules/123e4567-e89b-12d3-a456-426614174000/drive_d/Downloads/Setup.exe",
                WindowsInstallerKind.EXE,
                root
            )
            val msi = WineRuntimePlan.createInteractiveRunScript(
                "/opt/vyrox/prefixes/123e4567-e89b-12d3-a456-426614174000",
                "/mnt/vyrox/capsules/123e4567-e89b-12d3-a456-426614174000/drive_d/Downloads/Office.msi",
                WindowsInstallerKind.MSI,
                root
            )
            assertTrue(exe.contains("x11vnc -display :99 -localhost -nopw"))
            assertTrue(exe.contains("VYROX_WINDOWS_DESKTOP_READY"))
            assertTrue(exe.contains("VYROX_AUDIO_BRIDGE_STARTED"))
            assertTrue(exe.contains("Setup.exe"))
            assertTrue(msi.contains("msiexec /i"))
            assertTrue(msi.contains("Office.msi"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun iconExtractionUsesOnlyCapsuleCAndRealWindowsResourceTools() {
        val script = WineRuntimePlan.createIconExtractionScript("123e4567-e89b-12d3-a456-426614174000")
        assertTrue(script.contains("/mnt/vyrox/capsules/123e4567-e89b-12d3-a456-426614174000/drive_c"))
        assertTrue(script.contains("wrestool"))
        assertTrue(script.contains("icotool"))
    }

    @Test
    fun wineIsNotReadyWithoutInstalledPackageMetadataAndBox64() {
        val root = Files.createTempDirectory("vyrox-wine-empty").toFile()
        try {
            assertFalse(WineRuntimePlan.isInstalled(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun graphicsTranslationScriptDownloadsPinnedDxvkAndVkd3dAndInstallsIntoThisPrefix() {
        val root = wineRoot()
        try {
            val script = WineRuntimePlan.createGraphicsTranslationInstallScript(
                "/opt/vyrox/prefixes/123e4567-e89b-12d3-a456-426614174000",
                root
            )
            assertTrue(script.contains("dxvk-3.1.1.tar.gz"))
            assertTrue(script.contains("vkd3d-proton-3.1.tar.zst"))
            assertTrue(script.contains("setup_dxvk.sh"))
            assertTrue(script.contains("setup_vkd3d_proton.sh"))
            assertTrue(script.contains("VYROX_GRAPHICS_TRANSLATION_READY"))
            assertTrue(script.contains("VYROX_WINE_LOADER"))
            assertTrue(script.contains("WINEPREFIX"))
            assertFalse(script.contains("/usr/bin/wine "))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun graphicsTranslationRequiresDetectedVulkan() {
        val root = wineRoot()
        try {
            val metadata = File(root, WineRuntimePlan.METADATA_PATH)
            metadata.writeText(metadata.readText().replace(
                "vulkan_backend=lavapipe-software",
                "vulkan_backend=unavailable"
            ))
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                WineRuntimePlan.createGraphicsTranslationInstallScript(
                    "/opt/vyrox/prefixes/123e4567-e89b-12d3-a456-426614174000",
                    root
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun graphicsTranslationReadinessRequiresDllsAndMatchingMetadata() {
        val root = wineRoot()
        val capsuleC = Files.createTempDirectory("vyrox-graphics-capsule-c").toFile()
        try {
            val windows = File(capsuleC, "windows")
            val system32 = File(windows, "system32")
            val syswow64 = File(windows, "syswow64")
            check(system32.mkdirs())
            check(syswow64.mkdirs())
            val dxvkDlls = listOf("d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
            (dxvkDlls + listOf("d3d12.dll", "d3d12core.dll")).forEach {
                File(system32, it).writeText("dll")
            }
            dxvkDlls.forEach { File(syswow64, it).writeText("dll") }
            val prefix = File(root, "opt/vyrox/prefixes/123e4567-e89b-12d3-a456-426614174000")
            check(prefix.mkdirs())
            File(prefix, "vyrox-graphics.properties").writeText(
                """
                dxvk_version=3.1.1
                vkd3d_proton_version=3.1
                vulkan_backend=lavapipe-software
                """.trimIndent()
            )
            assertTrue(WineRuntimePlan.isGraphicsTranslationInstalled(prefix, capsuleC))
            File(syswow64, "d3d11.dll").delete()
            assertFalse(WineRuntimePlan.isGraphicsTranslationInstalled(prefix, capsuleC))
        } finally {
            root.deleteRecursively()
            capsuleC.deleteRecursively()
        }
    }

    private fun wineRoot(): File {
        val root = Files.createTempDirectory("vyrox-wine-plan").toFile()
        val loader = File(root, "usr/lib/wine/wine64")
        check(loader.parentFile.mkdirs())
        loader.writeBytes(elfHeader(62))
        loader.setExecutable(true)
        val boot = File(root, "usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe")
        check(boot.parentFile.mkdirs())
        boot.writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0, 0))
        val metadata = File(root, WineRuntimePlan.METADATA_PATH)
        check(metadata.parentFile.mkdirs())
        metadata.writeText(
            """
            version=wine-11.0
            major=11
            architecture=x86_64
            wow64_mode=new
            loader=/usr/lib/wine/wine64
            wineboot=/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe
            source=WineHQ-stable
            vulkan_backend=lavapipe-software
            """.trimIndent()
        )
        File(root, Box64BuildPlan.BINARY_RELATIVE_PATH).apply {
            parentFile.mkdirs()
            writeBytes(elfHeader(183))
            setExecutable(true)
        }
        return root
    }

    private fun elfHeader(machine: Int): ByteArray =
        byteArrayOf(
            0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(),
            2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            2, 0, (machine and 0xFF).toByte(), ((machine shr 8) and 0xFF).toByte()
        )
}
