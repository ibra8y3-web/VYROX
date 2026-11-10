package com.vyrox.runtime.core.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * Locates the optional PRoot PIE executable shipped in the APK native-library
 * payload. It never searches writable filesDir or extracts executable code.
 *
 * A successful result only confirms APK packaging preflight. Actual execve,
 * ptrace, and RootFS execution still require a physical Android device test.
 */
object ProotExecutableLocator {
    const val FILE_NAME = "libproot.so"
    private const val ARM64_ELF_MACHINE = 183
    private const val ELF_TYPE_DYN = 3
    private const val ELF_HEADER_BYTES = 64

    fun locate(
        nativeLibraryDirectory: File,
        supported64BitAbis: Collection<String>
    ): File? = runCatching {
        if ("arm64-v8a" !in supported64BitAbis) return null
        if (!nativeLibraryDirectory.isDirectory ||
            Files.isSymbolicLink(nativeLibraryDirectory.toPath())
        ) return null

        val nativeRoot = nativeLibraryDirectory.canonicalFile
        val candidatePath = File(nativeRoot, FILE_NAME)
        if (Files.isSymbolicLink(candidatePath.toPath())) return null

        val candidate = candidatePath.canonicalFile
        if (candidate.parentFile != nativeRoot ||
            !candidate.isFile ||
            !candidate.canExecute()
        ) return null
        if (!isArm64PieExecutable(candidate)) return null
        candidate
    }.getOrNull()

    private fun isArm64PieExecutable(file: File): Boolean {
        if (file.length() < ELF_HEADER_BYTES) return false
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(ELF_HEADER_BYTES)
            input.readFully(header)

            val validMagic = header[0] == 0x7F.toByte() &&
                header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte()
            val elfClass = header[4].toInt() and 0xFF
            val dataEncoding = header[5].toInt() and 0xFF
            val elfVersion = header[6].toInt() and 0xFF
            val elfType = readUnsignedShortLe(header, 16)
            val machine = readUnsignedShortLe(header, 18)
            val entryPoint = readUnsignedLongLe(header, 24)

            return validMagic &&
                elfClass == 2 &&
                dataEncoding == 1 &&
                elfVersion == 1 &&
                elfType == ELF_TYPE_DYN &&
                machine == ARM64_ELF_MACHINE &&
                entryPoint > 0L
        }
    }

    private fun readUnsignedShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUnsignedLongLe(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL)
        }
        return value
    }
}
