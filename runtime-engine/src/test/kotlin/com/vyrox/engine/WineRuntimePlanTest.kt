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
