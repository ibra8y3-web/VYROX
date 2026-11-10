package com.vyrox.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GraphicsTranslationPlanTest {
    @Test
    fun installScriptPinsUpstreamReleasesAndInstallsIntoTheSelectedPrefix() {
        val root = wineRoot()
        try {
            val script = GraphicsTranslationPlan.createInstallScript(
                "/opt/vyrox/prefixes/123e4567-e89b-12d3-a456-426614174000",
                root
            )
            assertTrue(script.contains("dxvk-3.1.1.tar.gz"))
            assertTrue(script.contains("vkd3d-proton-3.1.tar.zst"))
            assertTrue(script.contains("setup_dxvk.sh"))
            assertTrue(script.contains("--with-d3d10 --without-dxgi"))
            assertTrue(script.contains("readlink -f"))
            assertTrue(script.contains("/mnt/vyrox/capsules/*/drive_c"))
            assertTrue(script.contains("setup_vkd3d_proton.sh"))
            assertTrue(script.contains("VYROX_GRAPHICS_TRANSLATION_READY"))
            assertTrue(script.contains("WINEPREFIX"))
            assertTrue(script.contains("VYROX_WINE_LOADER"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installationDetectionRequiresBothDllSetsAndMetadata() {
        val root = Files.createTempDirectory("vyrox-graphics-root").toFile()
        val capsuleC = Files.createTempDirectory("vyrox-graphics-c").toFile()
        try {
            val prefix = File(root, "prefix")
            check(prefix.mkdirs())
            val windows = File(capsuleC, "Windows")
            val system32 = File(windows, "System32")
            val syswow64 = File(windows, "SysWOW64")
            check(system32.mkdirs())
            check(syswow64.mkdirs())
            val dxvkDlls = listOf("d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
            (dxvkDlls + listOf("d3d12.dll", "d3d12core.dll")).forEach {
                File(system32, it).writeText("dll")
            }
            dxvkDlls.forEach { File(syswow64, it).writeText("dll") }
            File(prefix, GraphicsTranslationPlan.METADATA_FILE).writeText(
                """
                dxvk_version=3.1.1
                vkd3d_proton_version=3.1
                vulkan_backend=lavapipe-software
                """.trimIndent()
            )
            assertTrue(GraphicsTranslationPlan.isInstalled(prefix, capsuleC))
            File(syswow64, "dxgi.dll").delete()
            assertFalse(GraphicsTranslationPlan.isInstalled(prefix, capsuleC))
        } finally {
            root.deleteRecursively()
            capsuleC.deleteRecursively()
        }
    }

    @Test
    fun graphicsScriptRequiresARealWineInstallation() {
        val root = Files.createTempDirectory("vyrox-graphics-empty").toFile()
        try {
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                GraphicsTranslationPlan.createInstallScript(
                    "/opt/vyrox/prefixes/123e4567-e89b-12d3-a456-426614174000",
                    root
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun wineRoot(): File {
        val root = Files.createTempDirectory("vyrox-graphics-wine").toFile()
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
        return root
    }

    private fun elfHeader(machine: Int): ByteArray =
        byteArrayOf(
            0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(),
            2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            2, 0, (machine and 0xFF).toByte(), ((machine shr 8) and 0xFF).toByte()
        )
}
