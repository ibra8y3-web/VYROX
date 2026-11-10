package com.vyrox.runtime.core.files

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.nio.file.Files
import java.util.Locale

data class ImportedInstaller(
    val displayName: String,
    val storedFileName: String,
    val sizeBytes: Long,
    val formatLabel: String = "Unverified",
    val architectureLabel: String = "Unknown architecture"
)

class InstallerStorage(context: Context) {
    private val installerDirectory = File(context.filesDir, "installers")

    @Throws(IOException::class, IllegalArgumentException::class)
    fun importInstaller(displayName: String, input: InputStream): ImportedInstaller {
        InstallerPolicy.requireSupportedInstaller(displayName)

        if (!installerDirectory.exists() && !installerDirectory.mkdirs()) {
            throw IOException("Could not create the private installer directory.")
        }

        val safeName = InstallerPolicy.sanitizeFileName(displayName)
        val temporaryFile = File(
            installerDirectory,
            ".import-" + UUID.randomUUID().toString() + ".part"
        )
        val destinationFile = File(
            installerDirectory,
            UUID.randomUUID().toString() + "_" + safeName
        )

        var totalBytes = 0L
        try {
            temporaryFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead == 0) continue

                    totalBytes += bytesRead
                    if (totalBytes > MAX_INSTALLER_BYTES) {
                        throw IOException("Installer exceeds the 2 GiB import limit.")
                    }
                    output.write(buffer, 0, bytesRead)
                }
            }

            if (totalBytes == 0L) {
                throw IOException("The selected installer file is empty.")
            }

            val inspection = WindowsBinaryInspector.inspect(temporaryFile, displayName)

            if (!temporaryFile.renameTo(destinationFile)) {
                throw IOException("Could not finalize the imported installer.")
            }

            return ImportedInstaller(
                displayName = safeName,
                storedFileName = destinationFile.name,
                sizeBytes = totalBytes,
                formatLabel = inspection.format.label,
                architectureLabel = inspection.architecture.label
            )
        } catch (exception: Exception) {
            temporaryFile.delete()
            destinationFile.delete()
            throw exception
        } finally {
            if (temporaryFile.exists()) {
                temporaryFile.delete()
            }
        }
    }

    fun listImported(): List<ImportedInstaller> {
        if (!installerDirectory.exists()) return emptyList()

        return installerDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { file ->
                val displayName = file.name.substringAfter('_', file.name)
                val inspection = runCatching {
                    WindowsBinaryInspector.inspect(file, displayName)
                }.getOrNull()
                ImportedInstaller(
                    displayName = displayName,
                    storedFileName = file.name,
                    sizeBytes = file.length(),
                    formatLabel = inspection?.format?.label ?: "Unverified",
                    architectureLabel = inspection?.architecture?.label ?: "Unknown architecture"
                )
            }
            .sortedByDescending { it.storedFileName }
    }

    /**
     * Opens a stored Windows installer only after checking its private path,
     * file identity, size and actual PE/MSI structure again.
     */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun openImportedInstaller(installer: ImportedInstaller): InputStream {
        val storedName = installer.storedFileName
        if (storedName.isBlank() || storedName.contains('/') || storedName.contains('\\')) {
            throw IllegalArgumentException("The stored installer name is invalid.")
        }
        val directory = installerDirectory.canonicalFile
        val candidate = File(installerDirectory, storedName)
        if (Files.isSymbolicLink(candidate.toPath())) {
            throw IOException("The stored installer cannot be a symbolic link.")
        }
        val file = candidate.canonicalFile
        if (file.parentFile != directory || !file.isFile || file.length() != installer.sizeBytes) {
            throw IOException("The imported installer is missing or has changed.")
        }

        val extension = installer.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val expectedFormat = when (extension) {
            "exe" -> WindowsBinaryFormat.PORTABLE_EXECUTABLE
            "msi" -> WindowsBinaryFormat.MSI_COMPOUND_DATABASE
            else -> throw IllegalArgumentException("Only EXE and MSI installers can be installed into a Capsule.")
        }
        val inspection = WindowsBinaryInspector.inspect(file, installer.displayName)
        if (inspection.format != expectedFormat) {
            throw IOException("The imported Windows installer failed its format validation.")
        }
        if (
            expectedFormat == WindowsBinaryFormat.PORTABLE_EXECUTABLE &&
            inspection.architecture !in setOf(WindowsArchitecture.X86, WindowsArchitecture.X86_64)
        ) {
            throw IOException("This x86-64 Wine runtime supports x86 and x86-64 Windows EXE files, not ARM executables.")
        }
        return FileInputStream(file)
    }

    fun deleteInstaller(storedFileName: String): Boolean {
        if (storedFileName.contains('/') || storedFileName.contains('\\')) return false

        val directory = installerDirectory.canonicalFile
        val candidate = File(installerDirectory, storedFileName).canonicalFile
        if (candidate.parentFile != directory) return false

        return candidate.isFile && candidate.delete()
    }

    companion object {
        const val MAX_INSTALLER_BYTES: Long = 2L * 1024L * 1024L * 1024L
    }
}
