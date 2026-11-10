package com.vyrox.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Box64BuildPlanTest {
    @Test
    fun buildRecipeUsesTheReviewedImmutableUpstreamCommitAndProotFlags() {
        val script = Box64BuildPlan.createShellScript()

        assertTrue(script.contains("BOX64_VERSION='0.4.4'"))
        assertTrue(script.contains("BOX64_COMMIT='2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a'"))
        assertTrue(script.contains("-DARM64=1"))
        assertTrue(script.contains("-DARM_DYNAREC=ON"))
        assertTrue(script.contains("-DBAD_SIGNAL=ON"))
        assertTrue(script.contains("cmake --build"))
        assertTrue(script.contains("cmake --install"))
        assertTrue(script.contains("x86_64-linux-gnu-as --64"))
        assertTrue(script.contains("x86_64-linux-gnu-ld -static"))
        assertTrue(script.contains("VYROX_BOX64_X86_64_SMOKE_OK"))
        assertTrue(script.contains("x86_64_smoke_test=passed"))
        assertTrue(script.contains("git init --template=/dev/null"))
        assertTrue(script.contains("\$BOX64_BINARY"))
        assertFalse(script.contains("@D@"))
        assertFalse(script.contains('\u0024'.toString().repeat(2)))
    }

    @Test
    fun installedCheckRejectsMissingAndUnmarkedBinaries() {
        val root = Files.createTempDirectory("vyrox-box64-test").toFile()
        try {
            assertFalse(Box64BuildPlan.isInstalled(root))

            val binary = File(root, Box64BuildPlan.BINARY_RELATIVE_PATH)
            assertTrue(binary.parentFile.mkdirs())
            binary.writeBytes(ByteArray(128))
            assertTrue(binary.setExecutable(true, false))

            val metadata = File(root, Box64BuildPlan.METADATA_RELATIVE_PATH)
            assertTrue(metadata.parentFile.mkdirs())
            metadata.writeText(validMetadata().replace("0.4.4", "0.4.3"))

            assertFalse(Box64BuildPlan.isInstalled(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installedCheckRequiresPinnedMetadataAndAarch64Elf() {
        val root = Files.createTempDirectory("vyrox-box64-elf-test").toFile()
        try {
            val binary = File(root, Box64BuildPlan.BINARY_RELATIVE_PATH)
            assertTrue(binary.parentFile.mkdirs())
            binary.writeBytes(aarch64PieHeader())
            assertTrue(binary.setExecutable(true, false))

            val metadata = File(root, Box64BuildPlan.METADATA_RELATIVE_PATH)
            assertTrue(metadata.parentFile.mkdirs())
            metadata.writeText(validMetadata())

            assertTrue(Box64BuildPlan.isInstalled(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installedCheckRejectsMissingX8664SmokeTestRecord() {
        val root = Files.createTempDirectory("vyrox-box64-no-smoke").toFile()
        try {
            val binary = File(root, Box64BuildPlan.BINARY_RELATIVE_PATH)
            assertTrue(binary.parentFile.mkdirs())
            binary.writeBytes(aarch64PieHeader())
            assertTrue(binary.setExecutable(true, false))

            val metadata = File(root, Box64BuildPlan.METADATA_RELATIVE_PATH)
            assertTrue(metadata.parentFile.mkdirs())
            metadata.writeText(validMetadata().replace("x86_64_smoke_test=passed\n", ""))

            assertFalse(Box64BuildPlan.isInstalled(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installedCheckRejectsSymbolicLinkInProtectedPath() {
        val root = Files.createTempDirectory("vyrox-box64-symlink").toFile()
        val external = Files.createTempDirectory("vyrox-box64-external").toFile()
        try {
            val binary = File(root, Box64BuildPlan.BINARY_RELATIVE_PATH)
            assertTrue(binary.parentFile.mkdirs())
            binary.writeBytes(aarch64PieHeader())
            assertTrue(binary.setExecutable(true, false))

            val metadata = File(root, Box64BuildPlan.METADATA_RELATIVE_PATH)
            assertTrue(metadata.parentFile.mkdirs())
            metadata.writeText(validMetadata())

            val protectedDirectory = File(root, "usr/local/share/vyrox")
            protectedDirectory.deleteRecursively()
            Files.createSymbolicLink(protectedDirectory.toPath(), external.toPath())

            assertFalse(Box64BuildPlan.isInstalled(root))
        } finally {
            root.deleteRecursively()
            external.deleteRecursively()
        }
    }

    private fun validMetadata(): String =
        "version=${Box64BuildPlan.VERSION}\n" +
            "source_commit=${Box64BuildPlan.SOURCE_COMMIT}\n" +
            "verification=version_aarch64_elf_x86_64_smoke\n" +
            "x86_64_smoke_test=passed\n"

    private fun aarch64PieHeader(): ByteArray {
        val bytes = ByteArray(128)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[6] = 1
        bytes[16] = 3
        bytes[18] = 183.toByte()
        bytes[19] = 0
        return bytes
    }
}
