package com.vyrox.runtime.core.files

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

enum class WindowsBinaryFormat(val label: String) {
    PORTABLE_EXECUTABLE("Windows PE executable"),
    MSI_COMPOUND_DATABASE("MSI compound database")
}

enum class WindowsArchitecture(val label: String) {
    X86("x86"),
    X86_64("x86-64"),
    ARM32("ARM32"),
    ARM64("ARM64"),
    UNKNOWN("Unknown architecture")
}

data class WindowsBinaryInspection(
    val format: WindowsBinaryFormat,
    val architecture: WindowsArchitecture,
    val machineCode: Int? = null,
    val optionalHeaderMagic: Int? = null
)

/**
 * Reads the actual binary header. A filename extension alone is not proof
 * that the selected file is a Windows executable or MSI database.
 */
object WindowsBinaryInspector {
    private val MSI_COMPOUND_SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
    )

    @Throws(IOException::class)
    fun inspect(file: File, displayName: String): WindowsBinaryInspection {
        if (!file.isFile || file.length() < 8L) {
            throw IOException("The selected installer is too small or is not a regular file.")
        }

        return when (displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "exe" -> inspectPortableExecutable(file)
            "msi" -> inspectMsiDatabase(file)
            else -> throw IOException("Only .exe and .msi files are supported.")
        }
    }

    private fun inspectPortableExecutable(file: File): WindowsBinaryInspection =
        RandomAccessFile(file, "r").use { input ->
            val dosMagic = ByteArray(2)
            input.readFully(dosMagic)
            if (dosMagic[0] != 'M'.code.toByte() || dosMagic[1] != 'Z'.code.toByte()) {
                throw IOException("The .exe file does not contain a valid DOS MZ header.")
            }

            input.seek(0x3C)
            val peOffset = readUnsignedIntLE(input)
            if (peOffset < 0x40L || peOffset > file.length() - 26L || peOffset > MAX_PE_HEADER_OFFSET) {
                throw IOException("The .exe file contains an invalid PE header offset.")
            }

            input.seek(peOffset)
            val signature = ByteArray(4)
            input.readFully(signature)
            if (!signature.contentEquals(byteArrayOf('P'.code.toByte(), 'E'.code.toByte(), 0, 0))) {
                throw IOException("The .exe file is missing the PE signature.")
            }

            val machine = readUnsignedShortLE(input)
            input.seek(peOffset + 24L)
            val optionalMagic = readUnsignedShortLE(input)
            val architecture = architectureForMachine(machine)
            if (architecture == WindowsArchitecture.UNKNOWN) {
                throw IOException("This PE machine type is not supported by the current VYROX importer.")
            }
            if ((architecture == WindowsArchitecture.X86 || architecture == WindowsArchitecture.ARM32) &&
                optionalMagic != PE32_MAGIC
            ) {
                throw IOException("The PE32 machine type has an unexpected optional-header format.")
            }
            if ((architecture == WindowsArchitecture.X86_64 || architecture == WindowsArchitecture.ARM64) &&
                optionalMagic != PE32_PLUS_MAGIC
            ) {
                throw IOException("The 64-bit PE machine type has an unexpected optional-header format.")
            }

            WindowsBinaryInspection(
                format = WindowsBinaryFormat.PORTABLE_EXECUTABLE,
                architecture = architecture,
                machineCode = machine,
                optionalHeaderMagic = optionalMagic
            )
        }

    private fun inspectMsiDatabase(file: File): WindowsBinaryInspection =
        RandomAccessFile(file, "r").use { input ->
            val signature = ByteArray(MSI_COMPOUND_SIGNATURE.size)
            input.readFully(signature)
            if (!signature.contentEquals(MSI_COMPOUND_SIGNATURE)) {
                throw IOException("The .msi file is not a valid Compound File Binary database.")
            }
            WindowsBinaryInspection(
                format = WindowsBinaryFormat.MSI_COMPOUND_DATABASE,
                architecture = WindowsArchitecture.UNKNOWN
            )
        }

    private fun architectureForMachine(machine: Int): WindowsArchitecture =
        when (machine) {
            IMAGE_FILE_MACHINE_I386 -> WindowsArchitecture.X86
            IMAGE_FILE_MACHINE_AMD64 -> WindowsArchitecture.X86_64
            IMAGE_FILE_MACHINE_ARM -> WindowsArchitecture.ARM32
            IMAGE_FILE_MACHINE_ARMNT -> WindowsArchitecture.ARM32
            IMAGE_FILE_MACHINE_ARM64 -> WindowsArchitecture.ARM64
            else -> WindowsArchitecture.UNKNOWN
        }

    private fun readUnsignedShortLE(input: RandomAccessFile): Int {
        val low = input.read()
        val high = input.read()
        if (low < 0 || high < 0) throw IOException("Unexpected end of PE header.")
        return low or (high shl 8)
    }

    private fun readUnsignedIntLE(input: RandomAccessFile): Long {
        var value = 0L
        repeat(4) { index ->
            val next = input.read()
            if (next < 0) throw IOException("Unexpected end of DOS header.")
            value = value or (next.toLong() shl (index * 8))
        }
        return value
    }

    private const val MAX_PE_HEADER_OFFSET = 16L * 1024L * 1024L
    private const val PE32_MAGIC = 0x10B
    private const val PE32_PLUS_MAGIC = 0x20B
    private const val IMAGE_FILE_MACHINE_I386 = 0x014C
    private const val IMAGE_FILE_MACHINE_AMD64 = 0x8664
    private const val IMAGE_FILE_MACHINE_ARM = 0x01C0
    private const val IMAGE_FILE_MACHINE_ARMNT = 0x01C4
    private const val IMAGE_FILE_MACHINE_ARM64 = 0xAA64
}
