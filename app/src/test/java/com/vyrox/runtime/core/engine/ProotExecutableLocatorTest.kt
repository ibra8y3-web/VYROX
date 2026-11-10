package com.vyrox.runtime.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ProotExecutableLocatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun locatesArm64PieExecutableInNativeLibraryDirectory() {
        val nativeDirectory = temporaryFolder.newFolder("native-lib")
        val executable = File(nativeDirectory, ProotExecutableLocator.FILE_NAME)
        writeElf(executable, machine = 183, elfType = 3, entryPoint = 0x1000)
        executable.setExecutable(true)
        writeRequiredLibraries(nativeDirectory)

        val found = ProotExecutableLocator.locate(nativeDirectory, listOf("arm64-v8a"))

        assertEquals(executable.canonicalFile, found)
    }

    @Test
    fun refusesUnsupportedHostAbi() {
        val nativeDirectory = temporaryFolder.newFolder("x86-native")
        val executable = File(nativeDirectory, ProotExecutableLocator.FILE_NAME)
        writeElf(executable, machine = 183, elfType = 3, entryPoint = 0x1000)
        executable.setExecutable(true)

        assertNull(ProotExecutableLocator.locate(nativeDirectory, listOf("x86_64")))
    }

    @Test
    fun rejectsWrongMachineElf32AndSharedLibraryImages() {
        val nativeDirectory = temporaryFolder.newFolder("invalid-native")
        val executable = File(nativeDirectory, ProotExecutableLocator.FILE_NAME)

        writeElf(executable, machine = 62, elfType = 3, entryPoint = 0x1000)
        executable.setExecutable(true)
        assertNull(ProotExecutableLocator.locate(nativeDirectory, listOf("arm64-v8a")))

        writeElf(executable, machine = 183, elfClass = 1, elfType = 3, entryPoint = 0x1000)
        assertNull(ProotExecutableLocator.locate(nativeDirectory, listOf("arm64-v8a")))

        writeElf(executable, machine = 183, elfType = 3, entryPoint = 0L)
        assertNull(ProotExecutableLocator.locate(nativeDirectory, listOf("arm64-v8a")))
    }

    @Test
    fun rejectsNonExecutableAndSymbolicLinkPayloads() {
        val nativeDirectory = temporaryFolder.newFolder("native-links")
        val executable = File(nativeDirectory, ProotExecutableLocator.FILE_NAME)
        writeElf(executable, machine = 183, elfType = 3, entryPoint = 0x1000)
        writeRequiredLibraries(nativeDirectory)
        executable.setExecutable(false)
        assertNull(ProotExecutableLocator.locate(nativeDirectory, listOf("arm64-v8a")))

        val outside = temporaryFolder.newFile("outside-proot")
        writeElf(outside, machine = 183, elfType = 3, entryPoint = 0x1000)
        outside.setExecutable(true)
        executable.delete()
        Files.createSymbolicLink(executable.toPath(), outside.toPath())
        assertNull(ProotExecutableLocator.locate(nativeDirectory, listOf("arm64-v8a")))
    }

    @Test
    fun rejectsProotWhenRequiredDynamicLibrariesAreMissing() {
        val nativeDirectory = temporaryFolder.newFolder("missing-libs")
        val executable = File(nativeDirectory, ProotExecutableLocator.FILE_NAME)
        writeElf(executable, machine = 183, elfType = 3, entryPoint = 0x1000)
        executable.setExecutable(true)
        writeRequiredLibraries(nativeDirectory)
        File(nativeDirectory, "libtalloc.so.2").delete()

        assertNull(ProotExecutableLocator.locate(nativeDirectory, listOf("arm64-v8a")))
    }

    @Test
    fun returnsNullWhenBinaryIsNotPackaged() {
        val nativeDirectory = temporaryFolder.newFolder("empty-native")
        assertNull(ProotExecutableLocator.locate(nativeDirectory, listOf("arm64-v8a")))
    }

    private fun writeRequiredLibraries(directory: File) {
        ProotExecutableLocator.REQUIRED_SHARED_LIBRARIES.forEach { name ->
            writeElf(File(directory, name), machine = 183, elfType = 3, entryPoint = 0L)
        }
    }

    private fun writeElf(
        file: File,
        machine: Int,
        elfClass: Int = 2,
        elfType: Int,
        entryPoint: Long
    ) {
        file.parentFile?.mkdirs()
        val header = ByteArray(64)
        header[0] = 0x7F.toByte()
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = elfClass.toByte()
        header[5] = 1
        header[6] = 1
        header[16] = (elfType and 0xFF).toByte()
        header[17] = ((elfType ushr 8) and 0xFF).toByte()
        header[18] = (machine and 0xFF).toByte()
        header[19] = ((machine ushr 8) and 0xFF).toByte()
        repeat(8) { index ->
            header[24 + index] = ((entryPoint ushr (index * 8)) and 0xFF).toByte()
        }
        file.writeBytes(header)
    }
}
