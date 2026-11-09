package com.vyrox.runtime.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimePackageManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installsSha256VerifiedPackageIntoPrivatePackageRoot() {
        val archive = zip("bin/runtime" to "runtime-binary".toByteArray())
        val manager = manager()
        val installed = manager.install(spec(archive), ByteArrayInputStream(archive))

        assertEquals("wine-runtime", installed.id)
        assertEquals("11.21-arm64", installed.version)
        assertTrue(File(installed.installationDirectory, "bin/runtime").isFile)
        assertEquals(listOf(installed.id), manager.listInstalled().map { it.id })
    }

    @Test
    fun rejectsWrongDigestAndCleansStagingFiles() {
        val archive = zip("bin/runtime" to byteArrayOf(1, 2, 3))
        val manager = manager()

        assertThrows(SecurityException::class.java) {
            manager.install(spec(archive).copy(sha256 = "0".repeat(64)), ByteArrayInputStream(archive))
        }
        assertTrue(manager.listInstalled().isEmpty())
        assertTrue(File(temporaryFolder.root, "runtime-packages/.staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsZipSlipAndDoesNotWriteOutsidePackageRoot() {
        val archive = zip("../escaped.txt" to "nope".toByteArray())
        val manager = manager()

        assertThrows(IOException::class.java) {
            manager.install(spec(archive), ByteArrayInputStream(archive))
        }
        assertFalse(File(temporaryFolder.root, "escaped.txt").exists())
        assertTrue(manager.listInstalled().isEmpty())
    }

    @Test
    fun rejectsPackageTargetForAnotherAbi() {
        val archive = zip("bin/runtime" to byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            manager().install(spec(archive).copy(hostAbi = "x86_64"), ByteArrayInputStream(archive))
        }
    }

    @Test
    fun enforcesExpandedArchiveLimit() {
        val archive = zip("bin/runtime" to ByteArray(128) { 7 })
        assertThrows(IOException::class.java) {
            manager().install(
                spec(archive).copy(maxExpandedBytes = 32),
                ByteArrayInputStream(archive)
            )
        }
    }

    @Test
    fun removesOnlyRequestedPackageVersion() {
        val archive = zip("bin/runtime" to "runtime".toByteArray())
        val manager = manager()
        val installed = manager.install(spec(archive), ByteArrayInputStream(archive))

        assertTrue(manager.remove(installed.id, installed.version))
        assertTrue(manager.listInstalled().isEmpty())
        assertFalse(manager.remove("../runtime", installed.version))
    }

    private fun manager() = RuntimePackageManager(
        File(temporaryFolder.root, "runtime-packages"),
        "arm64-v8a"
    )

    private fun spec(archive: ByteArray) = RuntimePackageSpec(
        id = "wine-runtime",
        version = "11.21-arm64",
        hostAbi = "arm64-v8a",
        sha256 = sha256(archive),
        license = "LGPL-2.1-or-later",
        sourceUrl = "https://example.invalid/runtime-package",
        requiredFiles = listOf("bin/runtime"),
        executablePaths = listOf("bin/runtime")
    )

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
