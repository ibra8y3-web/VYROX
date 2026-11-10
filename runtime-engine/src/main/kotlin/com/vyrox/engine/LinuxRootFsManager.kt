package com.vyrox.engine

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

enum class LinuxRootFsArchitecture(
    val directoryName: String,
    val elfMachine: Int,
    val label: String
) {
    ARM64("arm64", 183, "AArch64"),
    X86_64("x86_64", 62, "x86-64")
}

data class LinuxRootFsInfo(
    val architecture: LinuxRootFsArchitecture,
    val prettyName: String,
    val distributionId: String,
    val versionId: String,
    val importedArchiveName: String,
    val archiveSha256: String,
    val expandedBytes: Long,
    val entryCount: Int,
    val installedAtEpochMillis: Long
)

/**
 * Imports a user-supplied Linux RootFS tar archive into a Capsule.
 *
 * It does not download images, install Wine, run PRoot, or execute binaries.
 * Extraction is staged and validated before the RootFS is made visible.
 */
class LinuxRootFsManager(private val capsuleRoot: File) {

    fun listInstalled(capsuleId: String): List<LinuxRootFsInfo> {
        val capsule = resolveCapsule(capsuleId)
        val linuxDirectory = File(capsule, LINUX_DIRECTORY_NAME)
        if (!linuxDirectory.isDirectory) return emptyList()

        return LinuxRootFsArchitecture.entries.mapNotNull { architecture ->
            val root = File(linuxDirectory, rootFsDirectoryName(architecture))
            if (!root.isDirectory) null else readInfo(root)
        }
    }

    fun getInstalled(
        capsuleId: String,
        architecture: LinuxRootFsArchitecture
    ): LinuxRootFsInfo? {
        val capsule = resolveCapsule(capsuleId)
        val root = File(File(capsule, LINUX_DIRECTORY_NAME), rootFsDirectoryName(architecture))
        return if (root.isDirectory) readInfo(root) else null
    }

    fun getRootFsDirectory(
        capsuleId: String,
        architecture: LinuxRootFsArchitecture
    ): File? {
        val capsule = resolveCapsule(capsuleId)
        val linuxDirectory = File(capsule, LINUX_DIRECTORY_NAME).canonicalFile
        val root = File(linuxDirectory, rootFsDirectoryName(architecture)).canonicalFile
        return root.takeIf {
            it.parentFile == linuxDirectory && it.isDirectory && readInfo(it) != null
        }
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    fun importArchive(
        capsuleId: String,
        archiveName: String,
        input: InputStream
    ): LinuxRootFsInfo {
        requireSupportedArchiveName(archiveName)
        val capsule = resolveCapsule(capsuleId)
        val linuxDirectory = File(capsule, LINUX_DIRECTORY_NAME)
        if (!linuxDirectory.exists() && !linuxDirectory.mkdirs()) {
            throw IOException("Could not create the Capsule Linux runtime directory.")
        }

        val token = UUID.randomUUID().toString()
        val archive = File(linuxDirectory, ".rootfs-$token.archive")
        val staging = File(linuxDirectory, ".rootfs-stage-$token")
        var archiveBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            FileOutputStream(archive).buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue

                    archiveBytes += read
                    if (archiveBytes > MAX_COMPRESSED_BYTES) {
                        throw IOException("The RootFS archive exceeds the 4 GiB compressed-size limit.")
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
            if (archiveBytes == 0L) {
                throw IOException("The selected RootFS archive is empty.")
            }
            if (!staging.mkdir()) {
                throw IOException("Could not create the temporary RootFS extraction directory.")
            }

            val extraction = extractTarArchive(archive, archiveName, staging)
            val osReleaseFile = File(staging, "etc/os-release")
            if (!osReleaseFile.isFile) {
                throw IOException("RootFS is missing /etc/os-release.")
            }
            val shell = sequenceOf(
                File(staging, "bin/sh"),
                File(staging, "usr/bin/sh")
            ).firstOrNull { it.isFile }
                ?: throw IOException("RootFS is missing /bin/sh or /usr/bin/sh.")

            val architecture = detectElfArchitecture(shell)
                ?: throw IOException("The RootFS shell is not a supported 64-bit Linux ELF binary.")
            val osRelease = parseOsRelease(osReleaseFile)
            val info = LinuxRootFsInfo(
                architecture = architecture,
                prettyName = osRelease["PRETTY_NAME"] ?: osRelease["ID"] ?: "Linux",
                distributionId = osRelease["ID"] ?: "unknown",
                versionId = osRelease["VERSION_ID"] ?: "unknown",
                importedArchiveName = archiveName,
                archiveSha256 = digest.digest().toHexString(),
                expandedBytes = extraction.expandedBytes,
                entryCount = extraction.entryCount,
                installedAtEpochMillis = System.currentTimeMillis()
            )
            writeInfo(staging, info)

            val destination = File(linuxDirectory, rootFsDirectoryName(architecture))
            val backup = File(linuxDirectory, ".rootfs-backup-$token")
            var backedUp = false
            if (destination.exists()) {
                if (!destination.renameTo(backup)) {
                    throw IOException("Could not stage the previous RootFS for replacement.")
                }
                backedUp = true
            }

            if (!staging.renameTo(destination)) {
                if (backedUp) backup.renameTo(destination)
                throw IOException("Could not activate the imported RootFS.")
            }
            if (backedUp) backup.deleteRecursively()
            return info
        } catch (exception: Exception) {
            staging.deleteRecursively()
            throw exception
        } finally {
            if (archive.exists()) archive.delete()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun remove(capsuleId: String, architecture: LinuxRootFsArchitecture): Boolean {
        val capsule = resolveCapsule(capsuleId)
        val linuxDirectory = File(capsule, LINUX_DIRECTORY_NAME).canonicalFile
        if (!linuxDirectory.isDirectory) return false
        val target = File(linuxDirectory, rootFsDirectoryName(architecture)).canonicalFile
        if (target.parentFile != linuxDirectory || !target.isDirectory) return false
        return target.deleteRecursively()
    }

    private fun extractTarArchive(
        archive: File,
        archiveName: String,
        staging: File
    ): ExtractionResult {
        val buffered = BufferedInputStream(FileInputStream(archive))
        val decompressed: InputStream = when {
            archiveName.endsWith(".tar.gz", ignoreCase = true) ||
                archiveName.endsWith(".tgz", ignoreCase = true) ->
                GzipCompressorInputStream(buffered)
            archiveName.endsWith(".tar.xz", ignoreCase = true) ||
                archiveName.endsWith(".txz", ignoreCase = true) ->
                XZCompressorInputStream(buffered)
            archiveName.endsWith(".tar", ignoreCase = true) -> buffered
            else -> throw IOException("Unsupported RootFS archive format.")
        }

        val hardLinks = mutableListOf<PendingHardLink>()
        var expandedBytes = 0L
        var entryCount = 0

        TarArchiveInputStream(decompressed).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                entryCount += 1
                if (entryCount > MAX_ENTRIES) {
                    throw IOException("RootFS archive contains too many entries.")
                }
                if (!tar.canReadEntryData(entry)) {
                    throw IOException("RootFS archive contains an unreadable TAR entry.")
                }

                val relativePath = normalizeArchivePath(entry.name)
                if (relativePath == null) {
                    entry = tar.nextTarEntry
                    continue
                }

                val destination = resolveArchiveDestination(staging, relativePath)
                ensureParentDirectory(staging, destination.parentFile)

                when {
                    entry.isDirectory -> {
                        if (destination.exists() && !destination.isDirectory) {
                            throw IOException("TAR entry conflicts with an existing file: " + entry.name)
                        }
                        if (!destination.exists() && !destination.mkdir()) {
                            throw IOException("Could not create RootFS directory: " + entry.name)
                        }
                        applyMode(destination, entry.mode, isDirectory = true)
                    }

                    entry.isSymbolicLink -> {
                        if (destination.exists() || Files.isSymbolicLink(destination.toPath())) {
                            throw IOException("Duplicate RootFS symbolic link: " + entry.name)
                        }
                        createSafeSymbolicLink(staging, destination, relativePath, entry.linkName)
                    }

                    entry.isLink -> {
                        if (destination.exists() || Files.isSymbolicLink(destination.toPath())) {
                            throw IOException("Duplicate RootFS hard link: " + entry.name)
                        }
                        hardLinks += PendingHardLink(
                            destination = destination,
                            target = normalizeHardLinkTarget(entry.linkName)
                        )
                    }

                    entry.isFile -> {
                        if (destination.exists() || Files.isSymbolicLink(destination.toPath())) {
                            throw IOException("Duplicate RootFS file: " + entry.name)
                        }
                        val written = copyTarEntry(tar, destination, entry.size)
                        expandedBytes += written
                        if (expandedBytes > MAX_EXPANDED_BYTES) {
                            throw IOException("Expanded RootFS exceeds the 16 GiB extraction limit.")
                        }
                        applyMode(destination, entry.mode, isDirectory = false)
                    }

                    else -> {
                        // Device nodes, sockets, and FIFOs are deliberately not created.
                    }
                }
                entry = tar.nextTarEntry
            }
        }

        expandedBytes = resolveHardLinks(staging, hardLinks, expandedBytes)
        return ExtractionResult(expandedBytes, entryCount)
    }

    private fun copyTarEntry(
        tar: TarArchiveInputStream,
        destination: File,
        expectedSize: Long
    ): Long {
        if (expectedSize < 0L || expectedSize > MAX_EXPANDED_BYTES) {
            throw IOException("TAR entry has an invalid or excessive size.")
        }
        var copied = 0L
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = tar.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                copied += read
                if (copied > expectedSize || copied > MAX_EXPANDED_BYTES) {
                    throw IOException("TAR entry expanded beyond its declared size.")
                }
                output.write(buffer, 0, read)
            }
        }
        if (copied != expectedSize) {
            throw IOException("TAR entry size does not match the declared size.")
        }
        return copied
    }

    private fun resolveHardLinks(
        staging: File,
        links: MutableList<PendingHardLink>,
        initialExpandedBytes: Long
    ): Long {
        var expandedBytes = initialExpandedBytes
        var pending = links.toList()
        var progress: Boolean
        do {
            progress = false
            val next = mutableListOf<PendingHardLink>()
            for (link in pending) {
                val sourceCandidate = File(staging, link.target.toString())
                if (Files.isSymbolicLink(sourceCandidate.toPath())) {
                    throw IOException("RootFS hard links cannot target symbolic links.")
                }
                val source = resolveArchiveDestination(staging, link.target)
                if (!source.isFile) {
                    next += link
                    continue
                }
                ensureParentDirectory(staging, link.destination.parentFile)
                if (Files.exists(link.destination.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Hard-link destination already exists.")
                }

                val sourceSize = source.length()
                if (sourceSize < 0L) {
                    throw IOException("RootFS hard-link target has an invalid size.")
                }
                try {
                    Files.createLink(link.destination.toPath(), source.toPath())
                } catch (_: Exception) {
                    // Some app-private filesystems do not support hard links.
                    // A copy fallback consumes real disk space, so count it
                    // against the same expanded RootFS budget as regular files.
                    if (sourceSize > MAX_EXPANDED_BYTES - expandedBytes) {
                        throw IOException("Hard-link fallback copies exceed the 16 GiB extraction limit.")
                    }
                    var copied = 0L
                    source.inputStream().buffered().use { input ->
                        link.destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                copied += read
                                if (copied > sourceSize || copied > MAX_EXPANDED_BYTES - expandedBytes) {
                                    throw IOException("Hard-link fallback copy exceeded its size budget.")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (copied != sourceSize) {
                        throw IOException("Hard-link fallback copy did not match its source size.")
                    }
                    expandedBytes += copied
                }
                progress = true
            }
            pending = next
        } while (progress && pending.isNotEmpty())

        if (pending.isNotEmpty()) {
            throw IOException("RootFS contains a hard link whose target is missing or unsafe.")
        }
        return expandedBytes
    }

    private fun createSafeSymbolicLink(
        staging: File,
        destination: File,
        relativePath: Path,
        rawTarget: String
    ) {
        if (rawTarget.isBlank() || rawTarget.contains('\u0000') || rawTarget.contains('\\')) {
            throw IOException("RootFS contains an invalid symbolic-link target.")
        }
        val targetPath = try {
            Paths.get(rawTarget)
        } catch (exception: Exception) {
            throw IOException("RootFS contains an invalid symbolic-link path.", exception)
        }
        val rootRelativeTarget = if (targetPath.isAbsolute) {
            targetPath.root.relativize(targetPath)
        } else {
            relativePath.parent?.resolve(targetPath) ?: targetPath
        }.normalize()
        if (rootRelativeTarget.isAbsolute || rootRelativeTarget.startsWith("..")) {
            throw IOException("RootFS symbolic link escapes its extraction directory.")
        }

        val stagePath = staging.canonicalFile.toPath()
        val resolvedTarget = stagePath.resolve(rootRelativeTarget).normalize()
        if (!resolvedTarget.startsWith(stagePath)) {
            throw IOException("RootFS symbolic link escapes its extraction directory.")
        }
        val linkTarget = destination.parentFile.toPath().relativize(resolvedTarget)
        Files.createSymbolicLink(destination.toPath(), linkTarget)
    }

    private fun normalizeHardLinkTarget(rawTarget: String): Path {
        val clean = rawTarget.removePrefix("/")
        val path = try {
            Paths.get(clean).normalize()
        } catch (exception: Exception) {
            throw IOException("RootFS contains an invalid hard-link target.", exception)
        }
        if (path.isAbsolute || path.startsWith("..")) {
            throw IOException("RootFS hard link escapes its extraction directory.")
        }
        return path
    }

    private fun normalizeArchivePath(rawName: String): Path? {
        if (rawName.isBlank() || rawName.startsWith("/") ||
            rawName.startsWith("\\") || rawName.contains('\u0000') ||
            rawName.contains('\\') || rawName.contains(':')
        ) {
            throw IOException("RootFS archive contains an absolute or invalid path.")
        }
        val path = try {
            Paths.get(rawName).normalize()
        } catch (exception: Exception) {
            throw IOException("RootFS archive contains an invalid path.", exception)
        }
        if (path.isAbsolute || path.startsWith("..")) {
            throw IOException("RootFS archive contains a path traversal entry.")
        }
        return if (path.toString().isBlank() || path.toString() == ".") null else path
    }

    private fun resolveArchiveDestination(staging: File, relativePath: Path): File {
        val stagePath = staging.canonicalFile.toPath()
        val candidate = stagePath.resolve(relativePath).normalize()
        if (!candidate.startsWith(stagePath)) {
            throw IOException("RootFS archive path escaped the staging directory.")
        }
        val canonical = candidate.toFile().canonicalFile
        val root = staging.canonicalFile
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            throw IOException("RootFS archive path escaped the staging directory.")
        }
        return canonical
    }

    private fun ensureParentDirectory(staging: File, parent: File?) {
        if (parent == null) throw IOException("RootFS entry is missing a parent directory.")
        val canonicalParent = parent.canonicalFile
        val root = staging.canonicalFile
        if (canonicalParent != root && !canonicalParent.path.startsWith(root.path + File.separator)) {
            throw IOException("RootFS parent directory escaped the staging directory.")
        }
        if (!canonicalParent.exists() && !canonicalParent.mkdirs()) {
            throw IOException("Could not create a RootFS parent directory.")
        }
        if (!canonicalParent.isDirectory) {
            throw IOException("RootFS parent path is not a directory.")
        }
    }

    private fun applyMode(file: File, mode: Int, isDirectory: Boolean) {
        file.setReadable(true, false)
        file.setWritable((mode and 0b010010010) != 0, false)
        file.setExecutable(isDirectory || (mode and 0b001001001) != 0, false)
    }

    private fun detectElfArchitecture(file: File): LinuxRootFsArchitecture? {
        val header = ByteArray(20)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) return null
                offset += read
            }
        }
        if (
            header[0] != 0x7F.toByte() ||
            header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte()
        ) return null

        // VYROX's initial Android runtime target is 64-bit little-endian ELF.
        // The e_machine field alone is insufficient: an ELF32 file can carry
        // the same numeric machine identifier.
        val elfClass = header[4].toInt() and 0xFF
        val dataEncoding = header[5].toInt() and 0xFF
        val elfVersion = header[6].toInt() and 0xFF
        if (elfClass != 2 || dataEncoding != 1 || elfVersion != 1) return null

        val machine = (header[18].toInt() and 0xFF) or
            ((header[19].toInt() and 0xFF) shl 8)
        return LinuxRootFsArchitecture.entries.firstOrNull { it.elfMachine == machine }
    }

    private fun parseOsRelease(file: File): Map<String, String> =
        file.readLines()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null
                else {
                    val key = line.substring(0, separator)
                    val value = line.substring(separator + 1).trim().removeSurrounding("\"")
                    key to value
                }
            }
            .toMap()

    private fun writeInfo(directory: File, info: LinuxRootFsInfo) {
        val metadata = Properties().apply {
            setProperty("architecture", info.architecture.name)
            setProperty("prettyName", info.prettyName)
            setProperty("distributionId", info.distributionId)
            setProperty("versionId", info.versionId)
            setProperty("importedArchiveName", info.importedArchiveName)
            setProperty("archiveSha256", info.archiveSha256)
            setProperty("expandedBytes", info.expandedBytes.toString())
            setProperty("entryCount", info.entryCount.toString())
            setProperty("installedAtEpochMillis", info.installedAtEpochMillis.toString())
        }
        File(directory, ROOTFS_METADATA_FILE).outputStream().buffered().use {
            metadata.store(it, "VYROX Linux RootFS metadata")
        }
    }

    private fun readInfo(directory: File): LinuxRootFsInfo? {
        val metadataFile = File(directory, ROOTFS_METADATA_FILE)
        if (!metadataFile.isFile) return null
        return runCatching {
            val properties = Properties()
            metadataFile.inputStream().buffered().use { properties.load(it) }
            LinuxRootFsInfo(
                architecture = LinuxRootFsArchitecture.valueOf(properties.getProperty("architecture")),
                prettyName = properties.getProperty("prettyName"),
                distributionId = properties.getProperty("distributionId"),
                versionId = properties.getProperty("versionId"),
                importedArchiveName = properties.getProperty("importedArchiveName"),
                archiveSha256 = properties.getProperty("archiveSha256"),
                expandedBytes = properties.getProperty("expandedBytes").toLong(),
                entryCount = properties.getProperty("entryCount").toInt(),
                installedAtEpochMillis = properties.getProperty("installedAtEpochMillis").toLong()
            )
        }.getOrNull()
    }

    private fun resolveCapsule(capsuleId: String): File {
        require(CAPSULE_ID.matches(capsuleId)) { "Invalid Capsule identifier." }
        val root = capsuleRoot.canonicalFile
        val capsule = File(root, capsuleId).canonicalFile
        require(capsule.parentFile == root && capsule.isDirectory) {
            "Capsule storage directory was not found."
        }
        require(File(capsule, "capsule.properties").isFile) {
            "Capsule metadata was not found."
        }
        return capsule
    }

    private fun requireSupportedArchiveName(name: String) {
        require(
            name.endsWith(".tar", true) ||
                name.endsWith(".tar.gz", true) ||
                name.endsWith(".tgz", true) ||
                name.endsWith(".tar.xz", true) ||
                name.endsWith(".txz", true)
        ) {
            "Choose a .tar, .tar.gz, .tgz, .tar.xz, or .txz RootFS archive."
        }
    }

    private fun rootFsDirectoryName(architecture: LinuxRootFsArchitecture) =
        "rootfs-" + architecture.directoryName

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    private data class ExtractionResult(
        val expandedBytes: Long,
        val entryCount: Int
    )

    private data class PendingHardLink(
        val destination: File,
        val target: Path
    )

    companion object {
        private const val LINUX_DIRECTORY_NAME = "linux"
        private const val ROOTFS_METADATA_FILE = ".vyrox-rootfs.properties"
        private const val MAX_COMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MAX_EXPANDED_BYTES = 16L * 1024L * 1024L * 1024L
        private const val MAX_ENTRIES = 200_000
        private val CAPSULE_ID = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        )
    }
}
