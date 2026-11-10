package com.vyrox.runtime.core.capsule

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

data class CapsuleInfo(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long
)

data class CapsuleFileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
)

data class CapsuleSnapshotInfo(
    val fileName: String,
    val createdAtEpochMillis: Long,
    val sizeBytes: Long,
    val sha256: String
)

data class CapsuleSpaceUsage(
    val totalBytes: Long,
    val fileCount: Int,
    val directoryCount: Int
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

    fun listDirectory(
        capsuleId: String,
        driveLetter: Char,
        relativePath: String = ""
    ): List<CapsuleFileEntry> {
        val driveRoot = getDriveRoot(capsuleId, driveLetter)
            ?: throw IllegalArgumentException("Capsule drive not found.")
        val directory = resolveDrivePath(driveRoot, relativePath)
            ?: throw IllegalArgumentException("The requested path is outside this Capsule drive.")
        if (!directory.isDirectory) {
            throw IllegalArgumentException("The requested path is not a directory.")
        }

        val children = directory.listFiles()
            ?: throw IOException("Could not read the Capsule directory.")

        return children
            .filter {
                !it.name.startsWith(".vyrox-import-") &&
                    !it.name.startsWith(".vyrox-copy-") &&
                    !Files.isSymbolicLink(it.toPath())
            }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            .map { child ->
                CapsuleFileEntry(
                    name = child.name,
                    relativePath = joinRelativePath(relativePath, child.name),
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isFile) child.length() else 0L
                )
            }
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    fun createDirectory(
        capsuleId: String,
        driveLetter: Char,
        parentRelativePath: String,
        rawName: String
    ): CapsuleFileEntry {
        val name = CapsuleFilePolicy.requireValidEntryName(rawName)
        val driveRoot = getDriveRoot(capsuleId, driveLetter)
            ?: throw IllegalArgumentException("Capsule drive not found.")
        val parent = resolveDrivePath(driveRoot, parentRelativePath)
            ?: throw IllegalArgumentException("The parent path is outside this Capsule drive.")
        if (!parent.isDirectory) {
            throw IllegalArgumentException("The selected parent is not a directory.")
        }

        val destination = File(parent, name).canonicalFile
        if (destination.parentFile != parent.canonicalFile || !isWithinRoot(driveRoot, destination)) {
            throw IllegalArgumentException("The folder name is not valid for this location.")
        }
        if (hasCaseInsensitiveSibling(parent, name)) {
            throw IOException("An item with this name already exists (Windows ignores name casing).")
        }
        if (!destination.mkdir()) {
            throw IOException("Could not create the Capsule folder.")
        }

        return CapsuleFileEntry(
            name = name,
            relativePath = joinRelativePath(parentRelativePath, name),
            isDirectory = true,
            sizeBytes = 0L
        )
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    fun importFile(
        capsuleId: String,
        driveLetter: Char,
        parentRelativePath: String,
        displayName: String,
        input: InputStream
    ): CapsuleFileEntry {
        val name = CapsuleFilePolicy.requireValidEntryName(displayName)
        val driveRoot = getDriveRoot(capsuleId, driveLetter)
            ?: throw IllegalArgumentException("Capsule drive not found.")
        val parent = resolveDrivePath(driveRoot, parentRelativePath)
            ?: throw IllegalArgumentException("The destination path is outside this Capsule drive.")
        if (!parent.isDirectory) {
            throw IllegalArgumentException("The selected destination is not a directory.")
        }

        val destination = File(parent, name).canonicalFile
        if (destination.parentFile != parent.canonicalFile || !isWithinRoot(driveRoot, destination)) {
            throw IllegalArgumentException("The file name is not valid for this location.")
        }
        if (hasCaseInsensitiveSibling(parent, name)) {
            throw IOException("An item with this name already exists (Windows ignores name casing).")
        }

        val temporaryFile = File(parent, ".vyrox-import-" + UUID.randomUUID() + ".part")
        var totalBytes = 0L
        try {
            temporaryFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead == 0) continue

                    totalBytes += bytesRead
                    if (totalBytes > MAX_CAPSULE_FILE_BYTES) {
                        throw IOException("File exceeds the 2 GiB Capsule import limit.")
                    }
                    output.write(buffer, 0, bytesRead)
                }
            }

            if (totalBytes == 0L) {
                throw IOException("The selected file is empty.")
            }
            if (!temporaryFile.renameTo(destination)) {
                throw IOException("Could not finalize the imported Capsule file.")
            }

            return CapsuleFileEntry(
                name = name,
                relativePath = joinRelativePath(parentRelativePath, name),
                isDirectory = false,
                sizeBytes = totalBytes
            )
        } catch (exception: Exception) {
            temporaryFile.delete()
            throw exception
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    fun deleteEntry(capsuleId: String, driveLetter: Char, relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        val driveRoot = getDriveRoot(capsuleId, driveLetter) ?: return false
        val target = resolveDrivePath(driveRoot, relativePath) ?: return false
        if (target == driveRoot || !target.exists()) return false
        return if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

    fun renameEntry(
        capsuleId: String,
        driveLetter: Char,
        relativePath: String,
        rawNewName: String
    ): CapsuleFileEntry {
        val newName = CapsuleFilePolicy.requireValidEntryName(rawNewName)
        val driveRoot = getDriveRoot(capsuleId, driveLetter)
            ?: throw IllegalArgumentException("Capsule drive not found.")
        val source = resolveDrivePath(driveRoot, relativePath)
            ?: throw IllegalArgumentException("The source path is outside this Capsule drive.")
        if (source == driveRoot || !source.exists() || Files.isSymbolicLink(source.toPath())) {
            throw IllegalArgumentException("The selected item cannot be renamed.")
        }

        val parent = source.parentFile.canonicalFile
        if (hasCaseInsensitiveSibling(parent, newName, except = source)) {
            throw IOException("An item with this name already exists (Windows ignores name casing).")
        }
        val destination = File(parent, newName)
        if (destination.exists() && destination.canonicalFile != source.canonicalFile) {
            throw IOException("An item with this name already exists.")
        }
        if (!source.renameTo(destination)) {
            throw IOException("Could not rename the Capsule item.")
        }
        return toEntry(driveRoot, destination)
    }

    fun moveEntry(
        capsuleId: String,
        driveLetter: Char,
        sourceRelativePath: String,
        destinationDirectoryRelativePath: String
    ): CapsuleFileEntry {
        val driveRoot = getDriveRoot(capsuleId, driveLetter)
            ?: throw IllegalArgumentException("Capsule drive not found.")
        val source = resolveDrivePath(driveRoot, sourceRelativePath)
            ?: throw IllegalArgumentException("The source path is outside this Capsule drive.")
        val destinationDirectory = resolveDrivePath(driveRoot, destinationDirectoryRelativePath)
            ?: throw IllegalArgumentException("The destination path is outside this Capsule drive.")

        if (source == driveRoot || !source.exists() || Files.isSymbolicLink(source.toPath())) {
            throw IllegalArgumentException("The selected item cannot be moved.")
        }
        if (!destinationDirectory.isDirectory) {
            throw IllegalArgumentException("The destination is not a directory.")
        }
        if (source.isDirectory && isWithinRoot(source, destinationDirectory)) {
            throw IllegalArgumentException("A folder cannot be moved into itself or one of its children.")
        }
        if (source.parentFile.canonicalFile == destinationDirectory.canonicalFile) {
            return toEntry(driveRoot, source)
        }

        val destination = File(destinationDirectory, source.name)
        if (hasCaseInsensitiveSibling(destinationDirectory, source.name)) {
            throw IOException("An item with this name already exists (Windows ignores name casing).")
        }
        if (!source.renameTo(destination)) {
            throw IOException("Could not move the Capsule item.")
        }
        return toEntry(driveRoot, destination)
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    fun copyEntry(
        capsuleId: String,
        driveLetter: Char,
        sourceRelativePath: String,
        destinationDirectoryRelativePath: String
    ): CapsuleFileEntry {
        val driveRoot = getDriveRoot(capsuleId, driveLetter)
            ?: throw IllegalArgumentException("Capsule drive not found.")
        val source = resolveDrivePath(driveRoot, sourceRelativePath)
            ?: throw IllegalArgumentException("The source path is outside this Capsule drive.")
        val destinationDirectory = resolveDrivePath(driveRoot, destinationDirectoryRelativePath)
            ?: throw IllegalArgumentException("The destination path is outside this Capsule drive.")

        if (source == driveRoot || !source.exists() || Files.isSymbolicLink(source.toPath())) {
            throw IllegalArgumentException("The selected item cannot be copied.")
        }
        if (!destinationDirectory.isDirectory) {
            throw IllegalArgumentException("The destination is not a directory.")
        }
        if (source.isDirectory && isWithinRoot(source, destinationDirectory)) {
            throw IllegalArgumentException("A folder cannot be copied into itself or one of its children.")
        }
        if (hasCaseInsensitiveSibling(destinationDirectory, source.name)) {
            throw IOException("An item with this name already exists (Windows ignores name casing).")
        }

        val destination = File(destinationDirectory, source.name)
        val temporary = File(
            destinationDirectory,
            ".vyrox-copy-" + UUID.randomUUID() + ".part"
        )
        val budget = CopyBudget()
        try {
            copyNode(source, temporary, budget)
            if (!temporary.renameTo(destination)) {
                throw IOException("Could not finalize the Capsule copy.")
            }
            return toEntry(driveRoot, destination)
        } catch (exception: Exception) {
            temporary.deleteRecursively()
            throw exception
        } finally {
            if (temporary.exists()) temporary.deleteRecursively()
        }
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    fun extractZipArchive(
        capsuleId: String,
        driveLetter: Char,
        archiveRelativePath: String
    ): CapsuleFileEntry {
        val driveRoot = getDriveRoot(capsuleId, driveLetter)
            ?: throw IllegalArgumentException("Capsule drive not found.")
        val archive = resolveDrivePath(driveRoot, archiveRelativePath)
            ?: throw IllegalArgumentException("The ZIP archive is outside this Capsule drive.")
        if (!archive.isFile || !archive.name.endsWith(".zip", ignoreCase = true)) {
            throw IllegalArgumentException("Select a ZIP archive inside this Capsule.")
        }

        val parent = archive.parentFile.canonicalFile
        if (!isWithinRoot(driveRoot, parent)) {
            throw IllegalArgumentException("The ZIP destination is outside this Capsule drive.")
        }
        val folderName = CapsuleFilePolicy.requireValidEntryName(
            archive.name.substringBeforeLast('.', archive.name)
        )
        if (hasCaseInsensitiveSibling(parent, folderName)) {
            throw IOException("The extraction folder already exists.")
        }

        val destination = File(parent, folderName).canonicalFile
        if (destination.parentFile != parent || !isWithinRoot(driveRoot, destination)) {
            throw IllegalArgumentException("The extraction destination is invalid.")
        }

        val staging = File(parent, ".vyrox-extract-" + UUID.randomUUID()).canonicalFile
        if (staging.parentFile != parent || !staging.mkdir()) {
            throw IOException("Could not create a private extraction staging folder.")
        }

        val seenPaths = HashSet<String>()
        val budget = ZipExtractionBudget()
        try {
            ZipInputStream(archive.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    budget.entries += 1
                    if (budget.entries > MAX_ARCHIVE_ENTRIES) {
                        throw IOException("The ZIP archive contains too many entries.")
                    }

                    val relativeEntryPath = normalizeZipEntryPath(entry.name)
                    val collisionKey = relativeEntryPath.lowercase(Locale.ROOT)
                    if (!seenPaths.add(collisionKey)) {
                        throw IOException("The ZIP archive contains duplicate Windows paths.")
                    }

                    val target = File(staging, relativeEntryPath).canonicalFile
                    if (!isWithinRoot(staging, target)) {
                        throw IOException("The ZIP archive contains a path outside its extraction folder.")
                    }

                    val output = if (entry.isDirectory) {
                        if (target.exists() && !target.isDirectory) {
                            throw IOException("The ZIP archive contains a file/folder path conflict.")
                        }
                        if (!target.mkdirs() && !target.isDirectory) {
                            throw IOException("Could not create an extracted folder.")
                        }
                        null
                    } else {
                        val targetParent = target.parentFile
                            ?: throw IOException("The ZIP archive contains an invalid file path.")
                        if (!isWithinRoot(staging, targetParent)) {
                            throw IOException("The ZIP archive contains a path outside its extraction folder.")
                        }
                        if (!targetParent.mkdirs() && !targetParent.isDirectory) {
                            throw IOException("Could not create an extracted parent folder.")
                        }
                        if (target.exists()) {
                            throw IOException("The ZIP archive contains a duplicate output path.")
                        }
                        target.outputStream().buffered()
                    }

                    try {
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var entryBytes = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue

                            entryBytes += count
                            budget.totalBytes += count
                            if (
                                entryBytes > MAX_ARCHIVE_ENTRY_BYTES ||
                                budget.totalBytes > MAX_ARCHIVE_EXPANDED_BYTES
                            ) {
                                throw IOException("The ZIP archive exceeds the safe expanded-size limit.")
                            }
                            output?.write(buffer, 0, count)
                        }
                    } finally {
                        output?.close()
                    }
                }
            }

            if (budget.entries == 0) {
                throw IOException("The ZIP archive is empty.")
            }
            if (hasCaseInsensitiveSibling(parent, folderName)) {
                throw IOException("The extraction folder already exists.")
            }
            if (!staging.renameTo(destination)) {
                throw IOException("Could not finalize the extracted Capsule folder.")
            }
            return toEntry(driveRoot, destination)
        } catch (exception: Exception) {
            staging.deleteRecursively()
            throw exception
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun normalizeZipEntryPath(rawPath: String): String {
        if (rawPath.isBlank() || rawPath.length > MAX_ARCHIVE_PATH_LENGTH || rawPath.any { it.code == 0 }) {
            throw IOException("The ZIP archive contains an empty or oversized path.")
        }
        val normalized = rawPath.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.contains(':')) {
            throw IOException("The ZIP archive contains an absolute or drive-qualified path.")
        }
        val trimmed = normalized.removeSuffix("/")
        val segments = trimmed.split('/')
        if (trimmed.isBlank() || segments.any { it.isBlank() || it == "." || it == ".." }) {
            throw IOException("The ZIP archive contains a path traversal entry.")
        }
        return segments.joinToString("/") { CapsuleFilePolicy.requireValidEntryName(it) }
    }

    fun searchEntries(
        capsuleId: String,
        driveLetter: Char,
        query: String,
        maxResults: Int = 100
    ): List<CapsuleFileEntry> {
        require(query.isNotBlank()) { "Enter a search term." }
        require(maxResults in 1..MAX_SEARCH_RESULTS) {
            "Search result limit must be between 1 and $MAX_SEARCH_RESULTS."
        }
        val driveRoot = getDriveRoot(capsuleId, driveLetter)
            ?: throw IllegalArgumentException("Capsule drive not found.")

        val results = mutableListOf<CapsuleFileEntry>()
        val pending = ArrayDeque<File>()
        pending.add(driveRoot)
        var visited = 0

        while (pending.isNotEmpty() && results.size < maxResults && visited < MAX_SEARCH_VISITED) {
            val directory = pending.removeFirst()
            val children = directory.listFiles().orEmpty()
                .filter {
                    !it.name.startsWith(".vyrox-import-") &&
                        !it.name.startsWith(".vyrox-copy-") &&
                        !Files.isSymbolicLink(it.toPath())
                }
                .sortedBy { it.name.lowercase(Locale.ROOT) }

            for (child in children) {
                if (visited >= MAX_SEARCH_VISITED || results.size >= maxResults) break
                visited += 1
                val entry = toEntry(driveRoot, child)
                if (child.name.contains(query, ignoreCase = true)) {
                    results += entry
                }
                if (child.isDirectory) pending.addLast(child)
            }
        }
        return results
    }

    private fun toEntry(driveRoot: File, file: File): CapsuleFileEntry {
        val relativePath = file.canonicalFile.relativeTo(driveRoot.canonicalFile)
            .invariantSeparatorsPath
        return CapsuleFileEntry(
            name = file.name,
            relativePath = relativePath,
            isDirectory = file.isDirectory,
            sizeBytes = if (file.isFile) file.length() else 0L
        )
    }

    private fun copyNode(source: File, destination: File, budget: CopyBudget) {
        if (Files.isSymbolicLink(source.toPath())) {
            throw IOException("Symbolic links are not copied into Capsule workspaces.")
        }
        budget.entries += 1
        if (budget.entries > MAX_COPY_ENTRIES) {
            throw IOException("The copy contains too many files and folders.")
        }

        if (source.isDirectory) {
            if (!destination.mkdir()) {
                throw IOException("Could not create the destination folder.")
            }
            val children = source.listFiles()
                ?: throw IOException("Could not read a folder while copying.")
            children.forEach { child ->
                if (child.name.startsWith(".vyrox-import-") || child.name.startsWith(".vyrox-copy-")) {
                    return@forEach
                }
                copyNode(child, File(destination, child.name), budget)
            }
        } else if (source.isFile) {
            budget.totalBytes += source.length()
            if (budget.totalBytes > MAX_COPY_BYTES) {
                throw IOException("The selected copy exceeds the 8 GiB safety limit.")
            }
            source.inputStream().buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            throw IOException("Unsupported filesystem entry.")
        }
    }

    private data class ZipExtractionBudget(var entries: Int = 0, var totalBytes: Long = 0L)
    private data class ArchiveBudget(var entries: Int = 0, var bytes: Long = 0L)

    private data class CopyBudget(
        var entries: Int = 0,
        var totalBytes: Long = 0L
    )


    @Throws(IOException::class, IllegalArgumentException::class)
    fun renameCapsule(id: String, rawName: String): CapsuleInfo {
        val safeName = CapsulePolicy.normalizeName(rawName)
        val directory = requireCapsuleDirectory(id)
        val metadataFile = File(directory, CAPSULE_METADATA_FILE)
        val metadata = Properties()
        metadataFile.inputStream().buffered().use { metadata.load(it) }
        if (metadata.getProperty("id") != id) {
            throw IOException("Capsule metadata does not match its directory.")
        }
        metadata.setProperty("name", safeName)

        val temporary = File(directory, ".capsule-rename-" + UUID.randomUUID() + ".tmp")
        val backup = File(directory, ".capsule-metadata-" + UUID.randomUUID() + ".bak")
        try {
            temporary.outputStream().buffered().use { metadata.store(it, "VYROX Capsule metadata") }
            if (!metadataFile.renameTo(backup)) {
                throw IOException("Could not stage Capsule metadata for rename.")
            }
            if (!temporary.renameTo(metadataFile)) {
                backup.renameTo(metadataFile)
                throw IOException("Could not finalize the Capsule rename.")
            }
            backup.delete()
            return readCapsule(directory, capsuleRoot.canonicalFile)
                ?: throw IOException("Renamed Capsule metadata could not be reloaded.")
        } finally {
            temporary.delete()
            if (backup.exists() && !metadataFile.exists()) backup.renameTo(metadataFile)
        }
    }

    /**
     * Clone the file-backed C: and D: contents into a new Capsule.
     * This copies files only; it does not clone a running Windows prefix.
     */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun cloneCapsule(id: String, rawName: String): CapsuleInfo {
        val source = requireCapsuleDirectory(id)
        val clone = createCapsule(rawName)
        try {
            val budget = CopyBudget()
            val destination = requireCapsuleDirectory(clone.id)
            for (driveName in listOf("drive_c", "drive_d")) {
                val sourceDrive = File(source, driveName).canonicalFile
                val destinationDrive = File(destination, driveName).canonicalFile
                if (
                    sourceDrive.parentFile != source ||
                    destinationDrive.parentFile != destination ||
                    !sourceDrive.isDirectory ||
                    !destinationDrive.isDirectory
                ) {
                    throw IOException("Capsule drive layout is incomplete.")
                }
                sourceDrive.listFiles().orEmpty().forEach { child ->
                    copyNode(child, File(destinationDrive, child.name), budget)
                }
            }
            return clone
        } catch (exception: Exception) {
            deleteCapsule(clone.id)
            throw exception
        }
    }

    /**
     * Write a portable, versioned ZIP backup of a Capsule to the caller's stream.
     * The caller owns and closes the supplied stream.
     */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun exportCapsuleBackup(id: String, output: OutputStream) {
        val directory = requireCapsuleDirectory(id)
        val capsule = readCapsule(directory, capsuleRoot.canonicalFile)
            ?: throw IOException("Capsule metadata is invalid.")
        val zip = ZipOutputStream(output)
        val manifest = Properties().apply {
            setProperty("format", CAPSULE_BACKUP_FORMAT)
            setProperty("name", capsule.name)
            setProperty("createdAtEpochMillis", capsule.createdAtEpochMillis.toString())
        }
        val manifestBytes = ByteArrayOutputStream().also {
            manifest.store(it, "VYROX Capsule backup")
        }.toByteArray()
        zip.putNextEntry(ZipEntry(CAPSULE_BACKUP_MANIFEST))
        zip.write(manifestBytes)
        zip.closeEntry()

        val budget = ArchiveBudget()
        for (driveName in listOf("drive_c", "drive_d")) {
            val drive = File(directory, driveName).canonicalFile
            if (drive.parentFile != directory || !drive.isDirectory || Files.isSymbolicLink(drive.toPath())) {
                throw IOException("Capsule drive layout is missing or unsafe: $driveName")
            }
            writeBackupNode(drive, driveName, zip, budget)
        }
        zip.finish()
        zip.flush()
    }

    /**
     * Import a VYROX backup into a newly-created Capsule with a fresh identifier.
     */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun importCapsuleBackup(rawName: String, input: InputStream): CapsuleInfo {
        val safeName = CapsulePolicy.normalizeName(rawName)
        val staging = createStagingDirectory("backup-import")
        var created: CapsuleInfo? = null
        try {
            extractCapsuleBackup(input, staging)
            val importedC = File(staging, "drive_c")
            val importedD = File(staging, "drive_d")
            if (!importedC.isDirectory || !importedD.isDirectory) {
                throw IOException("Backup does not contain both Capsule drives.")
            }

            val capsule = createCapsule(safeName)
            created = capsule
            val destination = requireCapsuleDirectory(capsule.id)
            for (driveName in listOf("drive_c", "drive_d")) {
                val oldDrive = File(destination, driveName)
                val stagedDrive = File(staging, driveName)
                if (!oldDrive.deleteRecursively() || !stagedDrive.renameTo(oldDrive)) {
                    throw IOException("Could not restore Capsule drive: $driveName")
                }
            }
            return capsule
        } catch (exception: Exception) {
            created?.let { deleteCapsule(it.id) }
            throw exception
        } finally {
            staging.deleteRecursively()
        }
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    fun createSnapshot(id: String): CapsuleSnapshotInfo {
        val directory = requireCapsuleDirectory(id)
        val snapshots = File(directory, CAPSULE_SNAPSHOTS_DIRECTORY)
        if (!snapshots.exists() && !snapshots.mkdir()) {
            throw IOException("Could not create the Capsule snapshots directory.")
        }
        if (!snapshots.isDirectory || Files.isSymbolicLink(snapshots.toPath())) {
            throw SecurityException("Capsule snapshots path is not a private directory.")
        }
        val fileName = "snapshot-" + System.currentTimeMillis() + "-" +
            UUID.randomUUID().toString().take(8) + CAPSULE_SNAPSHOT_EXTENSION
        val snapshot = File(snapshots, fileName).canonicalFile
        if (snapshot.parentFile != snapshots.canonicalFile) {
            throw SecurityException("Snapshot path escaped the Capsule.")
        }
        try {
            snapshot.outputStream().buffered().use { exportCapsuleBackup(id, it) }
            return snapshotInfo(snapshot)
        } catch (exception: Exception) {
            snapshot.delete()
            throw exception
        }
    }

    fun listSnapshots(id: String): List<CapsuleSnapshotInfo> {
        val directory = requireCapsuleDirectory(id)
        val snapshots = File(directory, CAPSULE_SNAPSHOTS_DIRECTORY).canonicalFile
        if (!snapshots.isDirectory || snapshots.parentFile != directory) return emptyList()
        return snapshots.listFiles().orEmpty()
            .filter {
                it.isFile &&
                    !Files.isSymbolicLink(it.toPath()) &&
                    SNAPSHOT_FILE_PATTERN.matches(it.name) &&
                    it.canonicalFile.parentFile == snapshots
            }
            .mapNotNull { runCatching { snapshotInfo(it) }.getOrNull() }
            .sortedByDescending { it.createdAtEpochMillis }
    }

    /**
     * Restore a snapshot in-place. Both drives are staged before replacement;
     * previous drives are retained if either replacement fails.
     */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun restoreSnapshot(id: String, fileName: String) {
        val directory = requireCapsuleDirectory(id)
        require(SNAPSHOT_FILE_PATTERN.matches(fileName)) { "Snapshot name is invalid." }
        val snapshots = File(directory, CAPSULE_SNAPSHOTS_DIRECTORY).canonicalFile
        val snapshot = File(snapshots, fileName).canonicalFile
        if (
            snapshot.parentFile != snapshots ||
            !snapshot.isFile ||
            Files.isSymbolicLink(snapshot.toPath())
        ) {
            throw IOException("Snapshot file was not found inside this Capsule.")
        }

        val staging = createStagingDirectory("snapshot-restore")
        val token = UUID.randomUUID().toString()
        val oldC = File(directory, ".old-drive-c-$token")
        val oldD = File(directory, ".old-drive-d-$token")
        var movedOldC = false
        var movedOldD = false
        var installedC = false
        var installedD = false
        var succeeded = false
        try {
            snapshot.inputStream().buffered().use { extractCapsuleBackup(it, staging) }
            val stagedC = File(staging, "drive_c")
            val stagedD = File(staging, "drive_d")
            if (!stagedC.isDirectory || !stagedD.isDirectory) {
                throw IOException("Snapshot does not contain both Capsule drives.")
            }

            val currentC = File(directory, "drive_c")
            val currentD = File(directory, "drive_d")
            movedOldC = currentC.renameTo(oldC)
            if (!movedOldC) throw IOException("Could not stage the current C: drive.")
            movedOldD = currentD.renameTo(oldD)
            if (!movedOldD) throw IOException("Could not stage the current D: drive.")

            installedC = stagedC.renameTo(currentC)
            if (!installedC) throw IOException("Could not restore the C: drive.")
            installedD = stagedD.renameTo(currentD)
            if (!installedD) throw IOException("Could not restore the D: drive.")
            succeeded = true
        } catch (exception: Exception) {
            val currentC = File(directory, "drive_c")
            val currentD = File(directory, "drive_d")
            if (installedC) currentC.deleteRecursively()
            if (installedD) currentD.deleteRecursively()
            if (movedOldC && oldC.exists() && !oldC.renameTo(currentC)) {
                throw IOException("Restore failed and the previous C: drive remains at " + oldC.name, exception)
            }
            if (movedOldD && oldD.exists() && !oldD.renameTo(currentD)) {
                throw IOException("Restore failed and the previous D: drive remains at " + oldD.name, exception)
            }
            throw exception
        } finally {
            staging.deleteRecursively()
            if (succeeded) {
                oldC.deleteRecursively()
                oldD.deleteRecursively()
            }
        }
    }

    fun deleteSnapshot(id: String, fileName: String): Boolean {
        val directory = requireCapsuleDirectory(id)
        if (!SNAPSHOT_FILE_PATTERN.matches(fileName)) return false
        val snapshots = File(directory, CAPSULE_SNAPSHOTS_DIRECTORY).canonicalFile
        if (!snapshots.isDirectory || snapshots.parentFile != directory) return false
        val snapshot = File(snapshots, fileName).canonicalFile
        return snapshot.parentFile == snapshots &&
            snapshot.isFile &&
            !Files.isSymbolicLink(snapshot.toPath()) &&
            snapshot.delete()
    }

    fun calculateSpaceUsage(id: String): CapsuleSpaceUsage {
        val directory = requireCapsuleDirectory(id)
        var bytes = 0L
        var files = 0
        var folders = 0
        val pending = ArrayDeque<File>()
        pending.add(directory)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (Files.isSymbolicLink(current.toPath())) continue
            if (current.isDirectory) {
                folders += 1
                current.listFiles().orEmpty().forEach(pending::addLast)
            } else if (current.isFile) {
                files += 1
                bytes += current.length()
            }
        }
        return CapsuleSpaceUsage(bytes, files, folders)
    }

    private fun requireCapsuleDirectory(id: String): File {
        if (!isValidId(id) || !capsuleRoot.isDirectory) {
            throw IllegalArgumentException("Capsule identifier is invalid.")
        }
        val root = capsuleRoot.canonicalFile
        val directory = File(root, id).canonicalFile
        if (
            directory.parentFile != root ||
            !directory.isDirectory ||
            Files.isSymbolicLink(directory.toPath()) ||
            readCapsule(directory, root) == null
        ) {
            throw IOException("Capsule workspace was not found or its metadata is invalid.")
        }
        return directory
    }

    private fun createStagingDirectory(prefix: String): File {
        if (!capsuleRoot.exists() && !capsuleRoot.mkdirs()) {
            throw IOException("Could not create Capsule storage root.")
        }
        val root = capsuleRoot.canonicalFile
        if (!root.isDirectory || Files.isSymbolicLink(capsuleRoot.toPath())) {
            throw SecurityException("Capsule storage root is not a safe directory.")
        }
        val staging = File(root, ".$prefix-" + UUID.randomUUID()).canonicalFile
        if (staging.parentFile != root || !staging.mkdir()) {
            throw IOException("Could not create a private Capsule staging directory.")
        }
        return staging
    }

    private fun extractCapsuleBackup(input: InputStream, staging: File) {
        val seen = HashSet<String>()
        val budget = ArchiveBudget()
        var manifestSeen = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                budget.entries += 1
                if (budget.entries > MAX_ARCHIVE_ENTRIES) {
                    throw IOException("Capsule backup contains too many entries.")
                }
                val relativePath = normalizeZipEntryPath(entry.name)
                if (relativePath == CAPSULE_BACKUP_MANIFEST) {
                    if (entry.isDirectory || manifestSeen) {
                        throw IOException("Capsule backup manifest is invalid.")
                    }
                    val manifestText = zip.readBytesLimited(MAX_BACKUP_MANIFEST_BYTES)
                    val properties = Properties()
                    properties.load(manifestText.inputStream())
                    if (properties.getProperty("format") != CAPSULE_BACKUP_FORMAT) {
                        throw IOException("Unsupported VYROX Capsule backup format.")
                    }
                    manifestSeen = true
                    continue
                }

                val segments = relativePath.split('/')
                if (segments.firstOrNull() !in setOf("drive_c", "drive_d")) {
                    throw IOException("Backup contains data outside the Capsule drives.")
                }
                val key = relativePath.lowercase(Locale.ROOT)
                if (!seen.add(key)) throw IOException("Backup contains duplicate Windows paths.")

                val target = File(staging, relativePath).canonicalFile
                if (!isWithinRoot(staging, target) || target == staging.canonicalFile) {
                    throw IOException("Backup entry escaped its staging directory.")
                }
                if (entry.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw IOException("Could not create a backup folder.")
                    }
                    if (!target.isDirectory) throw IOException("Backup path conflicts with a file.")
                } else {
                    val parent = target.parentFile ?: throw IOException("Backup file has no parent.")
                    if (!isWithinRoot(staging, parent)) throw IOException("Backup parent escaped staging.")
                    if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create a backup parent.")
                    if (!parent.isDirectory || target.exists()) {
                        throw IOException("Backup contains a duplicate or conflicting path.")
                    }
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var fileBytes = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            fileBytes += count
                            budget.bytes += count
                            if (fileBytes > MAX_ARCHIVE_ENTRY_BYTES || budget.bytes > MAX_ARCHIVE_EXPANDED_BYTES) {
                                throw IOException("Backup expands beyond the configured size limit.")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        if (!manifestSeen || budget.entries == 0 ||
            !File(staging, "drive_c").isDirectory || !File(staging, "drive_d").isDirectory
        ) {
            throw IOException("Capsule backup is empty, invalid, or incomplete.")
        }
    }

    private fun writeBackupNode(source: File, archivePath: String, zip: ZipOutputStream, budget: ArchiveBudget) {
        if (Files.isSymbolicLink(source.toPath())) {
            throw IOException("Capsule backup cannot include symbolic links.")
        }
        budget.entries += 1
        if (budget.entries > MAX_ARCHIVE_ENTRIES) throw IOException("Capsule contains too many backup entries.")
        if (source.isDirectory) {
            zip.putNextEntry(ZipEntry(archivePath.trimEnd('/') + "/"))
            zip.closeEntry()
            source.listFiles()
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                ?.forEach { child -> writeBackupNode(child, "$archivePath/" + child.name, zip, budget) }
                ?: throw IOException("Could not read a Capsule folder during backup.")
        } else if (source.isFile) {
            budget.bytes += source.length()
            if (budget.bytes > MAX_ARCHIVE_EXPANDED_BYTES) {
                throw IOException("Capsule backup exceeds the configured size limit.")
            }
            zip.putNextEntry(ZipEntry(archivePath))
            source.inputStream().buffered().use { it.copyTo(zip) }
            zip.closeEntry()
        } else {
            throw IOException("Capsule contains an unsupported filesystem entry.")
        }
    }

    private fun snapshotInfo(file: File): CapsuleSnapshotInfo {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val manifest = readBackupManifest(file)
        return CapsuleSnapshotInfo(
            fileName = file.name,
            createdAtEpochMillis = manifest.getProperty("createdAtEpochMillis")?.toLongOrNull()
                ?: file.lastModified(),
            sizeBytes = file.length(),
            sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        )
    }

    private fun readBackupManifest(file: File): Properties {
        val properties = Properties()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == CAPSULE_BACKUP_MANIFEST) {
                    properties.load(zip.readBytesLimited(MAX_BACKUP_MANIFEST_BYTES).inputStream())
                    break
                }
            }
        }
        return properties
    }

    private fun InputStream.readBytesLimited(maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maximum) throw IOException("Capsule backup manifest is too large.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
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

    private fun resolveDrivePath(driveRoot: File, relativePath: String): File? {
        if (relativePath.isEmpty()) return driveRoot.canonicalFile
        val normalized = relativePath.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.endsWith("/") || normalized.contains(':')) {
            return null
        }
        val segments = normalized.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null

        var candidate = driveRoot.canonicalFile
        for (segment in segments) {
            val match = candidate.listFiles()
                ?.firstOrNull {
                    it.name.equals(segment, ignoreCase = true) &&
                        !Files.isSymbolicLink(it.toPath())
                }
                ?: return null
            candidate = match.canonicalFile
            if (!isWithinRoot(driveRoot, candidate)) return null
        }
        return candidate
    }

    private fun hasCaseInsensitiveSibling(
        parent: File,
        name: String,
        except: File? = null
    ): Boolean = parent.listFiles().orEmpty().any {
        it.name.equals(name, ignoreCase = true) &&
            (except == null || it.canonicalFile != except.canonicalFile)
    }

    private fun isWithinRoot(root: File, candidate: File): Boolean {
        val rootPath = root.canonicalPath
        val candidatePath = candidate.canonicalPath
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    private fun joinRelativePath(parent: String, name: String): String =
        if (parent.isBlank()) name else "$parent/$name"

    private fun readCapsule(directory: File, canonicalRoot: File): CapsuleInfo? {
        val canonicalDirectory = directory.canonicalFile
        if (canonicalDirectory.parentFile != canonicalRoot) return null

        val metadataFile = File(canonicalDirectory, CAPSULE_METADATA_FILE)
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
        const val MAX_CAPSULE_FILE_BYTES: Long = 2L * 1024L * 1024L * 1024L
        const val MAX_COPY_BYTES: Long = 8L * 1024L * 1024L * 1024L
        const val MAX_COPY_ENTRIES: Int = 10_000
        const val MAX_ARCHIVE_ENTRIES: Int = 10_000
        const val MAX_ARCHIVE_PATH_LENGTH: Int = 4_096
        const val MAX_ARCHIVE_ENTRY_BYTES: Long = 2L * 1024L * 1024L * 1024L
        const val MAX_ARCHIVE_EXPANDED_BYTES: Long = 2L * 1024L * 1024L * 1024L
        const val CAPSULE_METADATA_FILE = "capsule.properties"
        const val CAPSULE_BACKUP_FORMAT = "VYROX-CAPSULE-1"
        const val CAPSULE_BACKUP_MANIFEST = "vyrox-backup.properties"
        const val CAPSULE_SNAPSHOTS_DIRECTORY = "snapshots"
        const val CAPSULE_SNAPSHOT_EXTENSION = ".vxsnapshot"
        const val MAX_BACKUP_MANIFEST_BYTES = 16 * 1024
        const val MAX_SEARCH_RESULTS: Int = 500
        val SNAPSHOT_FILE_PATTERN = Regex("snapshot-[0-9]{10,17}-[0-9a-f]{8}\.vxsnapshot")
        const val MAX_SEARCH_VISITED: Int = 20_000

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
