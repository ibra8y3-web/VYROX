package com.vyrox.runtime.core.engine

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream

data class RuntimePackageSpec(
    val id: String,
    val version: String,
    val hostAbi: String,
    val sha256: String,
    val license: String,
    val sourceUrl: String,
    val requiredFiles: List<String>,
    val executablePaths: List<String> = emptyList(),
    val maxArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    val maxExpandedBytes: Long = DEFAULT_MAX_EXPANDED_BYTES,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    fun validateFor(expectedHostAbi: String) {
        require(PACKAGE_ID.matches(id)) { "Runtime package id is invalid." }
        require(PACKAGE_VERSION.matches(version)) { "Runtime package version is invalid." }
        require(ABI.matches(hostAbi) && hostAbi == expectedHostAbi) {
            "Runtime package ABI does not match this device."
        }
        require(SHA256.matches(sha256)) { "A full SHA-256 digest is required." }
        require(license.isNotBlank() && license.length <= 120) { "A license identifier is required." }
        require(sourceUrl.startsWith("https://")) { "Runtime package sources must use HTTPS." }
        require(requiredFiles.isNotEmpty()) { "At least one required runtime file must be declared." }
        requiredFiles.forEach(::validateRelativePath)
        executablePaths.forEach(::validateRelativePath)
        require(executablePaths.all { it in requiredFiles }) {
            "Every executable path must also be listed in requiredFiles."
        }
        require(maxArchiveBytes in 1..DEFAULT_MAX_ARCHIVE_BYTES) {
            "Archive size limit must be between 1 byte and 1 GiB."
        }
        require(maxExpandedBytes in 1..DEFAULT_MAX_EXPANDED_BYTES) {
            "Expanded size limit must be between 1 byte and 8 GiB."
        }
        require(maxEntries in 1..MAX_ALLOWED_ENTRIES) {
            "Archive entry limit is outside the allowed range."
        }
    }

    private fun validateRelativePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\')) {
            "Package file paths must be relative."
        }
        require(!path.contains('\\') && !path.contains(':')) {
            "Package file paths cannot contain backslashes or drive prefixes."
        }
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Package file paths contain an invalid segment."
        }
    }

    companion object {
        const val DEFAULT_MAX_ARCHIVE_BYTES = 1024L * 1024L * 1024L
        const val DEFAULT_MAX_EXPANDED_BYTES = 8L * 1024L * 1024L * 1024L
        const val DEFAULT_MAX_ENTRIES = 20_000
        const val MAX_ALLOWED_ENTRIES = 50_000

        private val PACKAGE_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val PACKAGE_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
        private val ABI = Regex("[a-z0-9_-]{1,32}")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}

data class InstalledRuntimePackage(
    val id: String,
    val version: String,
    val hostAbi: String,
    val sha256: String,
    val license: String,
    val sourceUrl: String,
    val installedAtEpochMillis: Long,
    val installationDirectory: File
)

/**
 * Installs verified runtime archives into app-private storage.
 *
 * This class never downloads packages and never executes their contents.
 * The caller supplies a trusted package specification and archive stream.
 */
class RuntimePackageManager(
    private val packageRoot: File,
    private val hostAbi: String
) {
    fun install(spec: RuntimePackageSpec, archive: InputStream): InstalledRuntimePackage {
        spec.validateFor(hostAbi)
        val root = prepareRoot()
        val stagingRoot = File(root, ".staging")
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
            throw IOException("Could not create runtime package staging directory.")
        }

        val staging = File(stagingRoot, UUID.randomUUID().toString())
        if (!staging.mkdir()) throw IOException("Could not create runtime package staging workspace.")

        try {
            val archiveFile = File(staging, "package.zip")
            val actualDigest = copyAndHashArchive(archive, archiveFile, spec.maxArchiveBytes)
            if (!actualDigest.equals(spec.sha256, ignoreCase = true)) {
                throw SecurityException("Runtime package SHA-256 does not match its manifest.")
            }

            val payload = File(staging, "payload")
            if (!payload.mkdir()) throw IOException("Could not create runtime package extraction directory.")
            extractArchive(archiveFile, payload, spec)

            spec.requiredFiles.forEach { required ->
                if (!resolveInside(payload, required).isFile) {
                    throw IOException("Runtime package is missing required file: $required")
                }
            }
            spec.executablePaths.forEach { relative ->
                val executable = resolveInside(payload, relative)
                if (!executable.setExecutable(true, false) && !executable.canExecute()) {
                    throw IOException("Could not set executable permission on: $relative")
                }
            }

            val installedAt = System.currentTimeMillis()
            writePackageMetadata(payload, spec, installedAt)

            val packageDirectory = File(root, spec.id).canonicalFile
            if (!isWithin(root, packageDirectory)) {
                throw IOException("Runtime package destination escaped the package root.")
            }
            val destinationParent = File(File(packageDirectory, spec.version), spec.hostAbi)
            if (destinationParent.exists()) {
                throw IOException("This runtime package version is already installed.")
            }
            if (!destinationParent.mkdirs()) {
                throw IOException("Could not create the runtime package destination.")
            }

            val installedDirectory = File(destinationParent, "payload")
            if (!payload.renameTo(installedDirectory)) {
                destinationParent.deleteRecursively()
                throw IOException("Could not finalize the runtime package installation.")
            }

            return InstalledRuntimePackage(
                id = spec.id,
                version = spec.version,
                hostAbi = spec.hostAbi,
                sha256 = actualDigest.lowercase(Locale.ROOT),
                license = spec.license,
                sourceUrl = spec.sourceUrl,
                installedAtEpochMillis = installedAt,
                installationDirectory = installedDirectory
            )
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun listInstalled(): List<InstalledRuntimePackage> {
        val root = packageRoot.canonicalFile
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && PACKAGE_ID.matches(it.name) }
            .flatMap { idDirectory ->
                idDirectory.listFiles().orEmpty()
                    .filter { it.isDirectory && PACKAGE_VERSION.matches(it.name) }
                    .flatMap { versionDirectory ->
                        versionDirectory.listFiles().orEmpty()
                            .filter { it.isDirectory && it.name == hostAbi }
                            .mapNotNull { abiDirectory ->
                                val payload = File(abiDirectory, "payload").canonicalFile
                                if (!isWithin(root, payload) || !payload.isDirectory) null
                                else readPackageMetadata(payload)
                            }
                    }
            }
            .sortedWith(compareBy({ it.id }, { it.version }))
    }

    fun remove(id: String, version: String): Boolean {
        if (!PACKAGE_ID.matches(id) || !PACKAGE_VERSION.matches(version)) return false
        val root = packageRoot.canonicalFile
        if (!root.isDirectory) return false
        val idDirectory = File(root, id).canonicalFile
        val versionDirectory = File(idDirectory, version).canonicalFile
        val abiDirectory = File(versionDirectory, hostAbi).canonicalFile
        if (!isWithin(root, abiDirectory) || abiDirectory.parentFile != versionDirectory) return false
        val metadata = readPackageMetadata(File(abiDirectory, "payload")) ?: return false
        if (metadata.id != id || metadata.version != version || metadata.hostAbi != hostAbi) return false
        return abiDirectory.deleteRecursively()
    }

    private fun prepareRoot(): File {
        if (!packageRoot.exists() && !packageRoot.mkdirs()) {
            throw IOException("Could not create the runtime package root.")
        }
        val root = packageRoot.canonicalFile
        if (!root.isDirectory) throw IOException("Runtime package root is not a directory.")
        return root
    }

    private fun copyAndHashArchive(input: InputStream, destination: File, maximumBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        input.use { source ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > maximumBytes) {
                        throw IOException("Runtime package archive exceeds its configured size limit.")
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        if (total == 0L) throw IOException("Runtime package archive is empty.")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractArchive(archive: File, destinationRoot: File, spec: RuntimePackageSpec) {
        val root = destinationRoot.canonicalFile
        val seenEntries = mutableSetOf<String>()
        var entryCount = 0
        var totalExpandedBytes = 0L

        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > spec.maxEntries) throw IOException("Runtime package has too many archive entries.")

                val relativePath = normalizeArchivePath(entry.name, entry.isDirectory)
                if (relativePath == PACKAGE_METADATA_FILE) {
                    throw IOException("Archive contains a reserved VYROX package metadata file.")
                }
                if (!seenEntries.add(relativePath)) {
                    throw IOException("Runtime package contains a duplicate path: $relativePath")
                }

                val target = resolveInside(root, relativePath)
                if (entry.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw IOException("Could not create runtime package folder: $relativePath")
                    }
                    if (!target.isDirectory) throw IOException("A package path conflicts with a non-directory.")
                } else {
                    val parent = target.parentFile ?: throw IOException("Package entry has no parent directory.")
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw IOException("Could not create a runtime package parent directory.")
                    }
                    if (!parent.isDirectory || target.exists()) {
                        throw IOException("Runtime package entry conflicts with an existing path.")
                    }
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            totalExpandedBytes += count
                            if (totalExpandedBytes > spec.maxExpandedBytes) {
                                throw IOException("Runtime package expands beyond its configured size limit.")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun writePackageMetadata(payload: File, spec: RuntimePackageSpec, installedAt: Long) {
        val metadata = Properties().apply {
            setProperty("id", spec.id)
            setProperty("version", spec.version)
            setProperty("hostAbi", spec.hostAbi)
            setProperty("sha256", spec.sha256.lowercase(Locale.ROOT))
            setProperty("license", spec.license)
            setProperty("sourceUrl", spec.sourceUrl)
            setProperty("installedAtEpochMillis", installedAt.toString())
            setProperty("requiredFiles", spec.requiredFiles.joinToString("\n"))
            setProperty("executablePaths", spec.executablePaths.joinToString("\n"))
        }
        File(payload, PACKAGE_METADATA_FILE).outputStream().buffered().use {
            metadata.store(it, "VYROX runtime package")
        }
    }

    private fun readPackageMetadata(payload: File): InstalledRuntimePackage? = runCatching {
        if (!payload.isDirectory) return null
        val metadata = Properties()
        File(payload, PACKAGE_METADATA_FILE).inputStream().buffered().use { metadata.load(it) }
        val id = metadata.getProperty("id") ?: return null
        val version = metadata.getProperty("version") ?: return null
        val abi = metadata.getProperty("hostAbi") ?: return null
        val sha = metadata.getProperty("sha256") ?: return null
        val license = metadata.getProperty("license") ?: return null
        val source = metadata.getProperty("sourceUrl") ?: return null
        val installedAt = metadata.getProperty("installedAtEpochMillis")?.toLongOrNull() ?: return null
        if (!PACKAGE_ID.matches(id) || !PACKAGE_VERSION.matches(version) ||
            abi != hostAbi || !SHA256.matches(sha) || license.isBlank() ||
            !source.startsWith("https://") || installedAt <= 0L
        ) return null
        InstalledRuntimePackage(id, version, abi, sha, license, source, installedAt, payload)
    }.getOrNull()

    private fun normalizeArchivePath(rawPath: String, isDirectory: Boolean): String {
        if (rawPath.isBlank() || rawPath.startsWith('/') || rawPath.startsWith('\\') ||
            rawPath.contains('\\') || rawPath.contains(':') || rawPath.any { it == '\u0000' }
        ) {
            throw IOException("Runtime package contains an absolute or invalid archive path.")
        }
        val normalized = if (isDirectory) rawPath.removeSuffix("/") else rawPath
        val segments = normalized.split('/')
        if (segments.isEmpty() || segments.any { it.isBlank() || it == "." || it == ".." }) {
            throw IOException("Runtime package contains a path traversal entry.")
        }
        return segments.joinToString(File.separator)
    }

    private fun resolveInside(root: File, relativePath: String): File {
        val candidate = File(root, relativePath).canonicalFile
        if (!isWithin(root, candidate) || candidate == root.canonicalFile) {
            throw IOException("Runtime package entry escaped its staging directory.")
        }
        return candidate
    }

    private fun isWithin(root: File, candidate: File): Boolean =
        candidate.canonicalPath.startsWith(root.canonicalPath + File.separator)

    companion object {
        private const val PACKAGE_METADATA_FILE = ".vyrox-package.properties"
        private val PACKAGE_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val PACKAGE_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}
