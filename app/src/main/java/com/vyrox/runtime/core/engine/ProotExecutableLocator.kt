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
    const val LOADER_FILE_NAME = "libproot-loader.so"
    private const val ARM64_ELF_MACHINE = 183
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
            !candidate.canExecute() ||
            !isArm64PieExecutable(candidate)
        ) return null

        val loaderPath = File(nativeRoot, LOADER_FILE_NAME)
        if (Files.isSymbolicLink(loaderPath.toPath())) return null
        val loader = loaderPath.canonicalFile
        if (
            loader.parentFile != nativeRoot ||
            !loader.isFile ||
            !loader.canExecute() ||
            !isArm64StaticLoader(loader)
        ) return null

        candidate
    }.getOrNull()

    private fun isArm64PieExecutable(file: File): Boolean {
        val header = readElfHeader(file) ?: return false
        return isArm64ElfHeader(header) &&
            readUnsignedShortLe(header, 16) == 3 &&
            readUnsignedLongLe(header, 24) > 0L
    }

    private fun isArm64StaticLoader(file: File): Boolean {
        val header = readElfHeader(file) ?: return false
        return isArm64ElfHeader(header) &&
            readUnsignedShortLe(header, 16) == 2 &&
            readUnsignedLongLe(header, 24) > 0L
    }

    private fun readElfHeader(file: File): ByteArray? {
        if (file.length() < ELF_HEADER_BYTES) return null
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                ByteArray(ELF_HEADER_BYTES).also(input::readFully)
            }
        }.getOrNull()
    }

    private fun isArm64ElfHeader(header: ByteArray): Boolean {
        val validMagic = header[0] == 0x7F.toByte() &&
            header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte()
        return validMagic &&
            (header[4].toInt() and 0xFF) == 2 &&
            (header[5].toInt() and 0xFF) == 1 &&
            (header[6].toInt() and 0xFF) == 1 &&
            readUnsignedShortLe(header, 18) == ARM64_ELF_MACHINE
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
