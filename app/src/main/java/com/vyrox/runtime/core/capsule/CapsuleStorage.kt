package com.vyrox.runtime.core.capsule

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Locale
import java.util.Properties
import java.util.UUID

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

    private data class CopyBudget(
        var entries: Int = 0,
        var totalBytes: Long = 0L
    )


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
        const val MAX_CAPSULE_FILE_BYTES: Long = 2L * 1024L * 1024L * 1024L
        const val MAX_COPY_BYTES: Long = 8L * 1024L * 1024L * 1024L
        const val MAX_COPY_ENTRIES: Int = 10_000
        const val MAX_SEARCH_RESULTS: Int = 500
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
