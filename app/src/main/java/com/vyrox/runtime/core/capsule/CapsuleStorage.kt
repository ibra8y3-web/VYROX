package com.vyrox.runtime.core.capsule

import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID

data class CapsuleInfo(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long
)

/**
 * File-based Capsule workspace storage.
 * This is not a virtual machine, process sandbox, or Windows runtime.
 */
class CapsuleStorage(private val capsuleRoot: File) {

    fun listCapsules(): List<CapsuleInfo> {
        if (!capsuleRoot.isDirectory) return emptyList()
        val canonicalRoot = capsuleRoot.canonicalFile
        return capsuleRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && isValidId(it.name) }
            .mapNotNull { directory ->
                runCatching { readCapsule(directory, canonicalRoot) }.getOrNull()
            }
            .sortedByDescending { it.createdAtEpochMillis }
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    fun createCapsule(rawName: String): CapsuleInfo {
        val safeName = CapsulePolicy.normalizeName(rawName)
        if (!capsuleRoot.exists() && !capsuleRoot.mkdirs()) {
            throw IOException("Could not create the Capsule storage directory.")
        }

        val capsuleId = UUID.randomUUID().toString()
        val directory = File(capsuleRoot, capsuleId)
        if (!directory.mkdir()) {
            throw IOException("Could not create the Capsule workspace.")
        }

        val createdAt = System.currentTimeMillis()
        val metadata = Properties().apply {
            setProperty("id", capsuleId)
            setProperty("name", safeName)
            setProperty("createdAtEpochMillis", createdAt.toString())
        }
        val temporaryFile = File(directory, ".capsule.properties.tmp")
        val metadataFile = File(directory, "capsule.properties")

        try {
            createInitialDriveLayout(directory)
            temporaryFile.outputStream().buffered().use { output ->
                metadata.store(output, "VYROX Capsule metadata")
            }
            if (!temporaryFile.renameTo(metadataFile)) {
                throw IOException("Could not finalize Capsule metadata.")
            }
            return CapsuleInfo(capsuleId, safeName, createdAt)
        } catch (exception: Exception) {
            directory.deleteRecursively()
            throw exception
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    /**
     * Returns a file-backed C: or D: drive root for the requested Capsule.
     * The returned directory is private to the app; it is not an OS-mounted drive.
     */
    fun getDriveRoot(id: String, driveLetter: Char): File? {
        if (!isValidId(id) || !capsuleRoot.isDirectory) return null

        val canonicalRoot = capsuleRoot.canonicalFile
        val capsuleDirectory = File(capsuleRoot, id).canonicalFile
        if (capsuleDirectory.parentFile != canonicalRoot || !capsuleDirectory.isDirectory) {
            return null
        }

        val driveDirectoryName = when (driveLetter.uppercaseChar()) {
            'C' -> "drive_c"
            'D' -> "drive_d"
            else -> return null
        }
        val driveDirectory = File(capsuleDirectory, driveDirectoryName).canonicalFile
        if (driveDirectory.parentFile != capsuleDirectory || !driveDirectory.isDirectory) {
            return null
        }
        return driveDirectory
    }

    fun deleteCapsule(id: String): Boolean {
        if (!isValidId(id) || !capsuleRoot.isDirectory) return false
        val canonicalRoot = capsuleRoot.canonicalFile
        val candidate = File(capsuleRoot, id).canonicalFile
        if (candidate.parentFile != canonicalRoot || !candidate.isDirectory) return false
        return candidate.deleteRecursively()
    }

    private fun createInitialDriveLayout(capsuleDirectory: File) {
        INITIAL_DIRECTORY_PATHS.forEach { relativePath ->
            val target = File(capsuleDirectory, relativePath)
            if (!target.mkdirs() && !target.isDirectory) {
                throw IOException("Could not create Capsule directory: $relativePath")
            }
        }
    }

    private fun readCapsule(directory: File, canonicalRoot: File): CapsuleInfo? {
        val canonicalDirectory = directory.canonicalFile
        if (canonicalDirectory.parentFile != canonicalRoot) return null

        val metadataFile = File(canonicalDirectory, "capsule.properties")
        if (!metadataFile.isFile) return null

        val metadata = Properties()
        metadataFile.inputStream().buffered().use { input -> metadata.load(input) }

        val id = metadata.getProperty("id")?.takeIf {
            it == directory.name && isValidId(it)
        } ?: return null
        val name = runCatching {
            CapsulePolicy.normalizeName(metadata.getProperty("name").orEmpty())
        }.getOrNull() ?: return null
        val createdAt = metadata.getProperty("createdAtEpochMillis")
            ?.toLongOrNull()
            ?.takeIf { it > 0L } ?: return null

        return CapsuleInfo(id, name, createdAt)
    }

    private fun isValidId(value: String): Boolean = CAPSULE_ID_PATTERN.matches(value)

    private companion object {
        val INITIAL_DIRECTORY_PATHS = listOf(
            "drive_c/Windows",
            "drive_c/Program Files",
            "drive_c/Program Files (x86)",
            "drive_c/Users/Default",
            "drive_c/Users/Public",
            "drive_c/Temp",
            "drive_d/Downloads",
            "drive_d/Documents",
            "drive_d/Games",
            "drive_d/Data"
        )

        val CAPSULE_ID_PATTERN = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        )
    }
}
