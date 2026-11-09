package com.vyrox.runtime.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class WindowsBinaryInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun detectsX64PeExecutableFromItsMachineHeader() {
        val file = createPeFile(machine = 0x8664, optionalMagic = 0x20B)
        val result = WindowsBinaryInspector.inspect(file, "setup.exe")
        assertEquals(WindowsBinaryFormat.PORTABLE_EXECUTABLE, result.format)
        assertEquals(WindowsArchitecture.X86_64, result.architecture)
    }

    @Test
    fun detectsArm64PeExecutable() {
        val file = createPeFile(machine = 0xAA64, optionalMagic = 0x20B)
        val result = WindowsBinaryInspector.inspect(file, "arm-app.exe")
        assertEquals(WindowsArchitecture.ARM64, result.architecture)
    }

    @Test
    fun rejectsFileThatOnlyHasExeExtension() {
        val file = temporaryFolder.newFile("not-really.exe")
        file.writeText("not a PE file")
        assertThrows(java.io.IOException::class.java) {
            WindowsBinaryInspector.inspect(file, file.name)
        }
    }

    @Test
    fun rejectsInconsistentPe32Header() {
        val file = createPeFile(machine = 0x8664, optionalMagic = 0x10B)
        assertThrows(java.io.IOException::class.java) {
            WindowsBinaryInspector.inspect(file, "mismatched.exe")
        }
    }

    @Test
    fun recognizesMsiCompoundFileHeader() {
        val file = temporaryFolder.newFile("package.msi")
        file.writeBytes(
            byteArrayOf(
                0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
                0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
            ) + ByteArray(32)
        )
        val result = WindowsBinaryInspector.inspect(file, file.name)
        assertEquals(WindowsBinaryFormat.MSI_COMPOUND_DATABASE, result.format)
        assertEquals(WindowsArchitecture.UNKNOWN, result.architecture)
    }

    private fun createPeFile(machine: Int, optionalMagic: Int): File {
        val file = temporaryFolder.newFile("test.exe")
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0x80L + 26L)
            output.seek(0)
            output.write('M')
            output.write('Z')
            output.seek(0x3C)
            writeIntLE(output, 0x80)
            output.seek(0x80)
            output.write(byteArrayOf('P'.code.toByte(), 'E'.code.toByte(), 0, 0))
            writeShortLE(output, machine)
            output.seek(0x80L + 24L)
            writeShortLE(output, optionalMagic)
        }
        return file
    }

    private fun writeShortLE(output: RandomAccessFile, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }

    private fun writeIntLE(output: RandomAccessFile, value: Int) {
        repeat(4) { index ->
            output.write((value ushr (index * 8)) and 0xFF)
        }
    }
}
