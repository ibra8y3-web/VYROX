package com.vyrox.runtime.core.files

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

data class ImportedInstaller(
    val displayName: String,
    val storedFileName: String,
    val sizeBytes: Long
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

            if (!temporaryFile.renameTo(destinationFile)) {
                throw IOException("Could not finalize the imported installer.")
            }

            return ImportedInstaller(
                displayName = safeName,
                storedFileName = destinationFile.name,
                sizeBytes = totalBytes
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
                ImportedInstaller(
                    displayName = file.name.substringAfter('_', file.name),
                    storedFileName = file.name,
                    sizeBytes = file.length()
                )
            }
            .sortedByDescending { it.storedFileName }
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
