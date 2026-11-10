package com.vyrox.runtime.core.engine

import java.io.File
import java.io.FileInputStream
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

        if (!isArm64Elf64(candidate)) return null
        candidate
    }.getOrNull()

    private fun isArm64Elf64(file: File): Boolean {
        val header = ByteArray(20)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) return false
                if (count > 0) offset += count
            }
        }

        val validMagic = header[0] == 0x7F.toByte() &&
            header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte()
        val elfClass = header[4].toInt() and 0xFF
        val dataEncoding = header[5].toInt() and 0xFF
        val elfVersion = header[6].toInt() and 0xFF
        val machine = (header[18].toInt() and 0xFF) or
            ((header[19].toInt() and 0xFF) shl 8)

        return validMagic &&
            elfClass == 2 &&
            dataEncoding == 1 &&
            elfVersion == 1 &&
            machine == ARM64_ELF_MACHINE
    }
}
