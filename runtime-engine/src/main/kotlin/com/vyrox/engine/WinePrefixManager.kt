package com.vyrox.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

data class WinePrefixCreationTicket internal constructor(
    val capsuleId: String,
    val stagingDirectory: File,
    val guestPrefixPath: String,
    val guestDriveCPath: String,
    val guestDriveDPath: String
)

data class WinePrefixInfo(
    val capsuleId: String,
    val directory: File,
    val wineVersion: String,
    val architecture: String,
    val createdAtEpochMillis: Long,
    val guestDriveCPath: String,
    val guestDriveDPath: String
)

/**
 * Transactional storage and integrity gate for per-Capsule Wine prefixes.
 *
 * This class does not create fake registry files and does not install Wine.
 * A caller must first execute the real Wine 11+ wineboot -u process inside
 * PRoot, then pass its exit code and real wine --version output to finalize.
 */
class WinePrefixManager(
    private val rootFsDirectory: File
) {
    fun prepareCreation(capsuleId: String): WinePrefixCreationTicket {
        requireValidCapsuleId(capsuleId)
        val root = requireRootFs()
        val prefixRoot = ensureSafeDirectory(root, listOf("opt", "vyrox", "prefixes"))
        val destination = File(prefixRoot, capsuleId).canonicalFile
        if (
            destination.parentFile != prefixRoot ||
            Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("A Wine prefix already exists for this Capsule.")
        }

        val staging = File(
            prefixRoot,
            ".staging-" + capsuleId + "-" + UUID.randomUUID()
        ).canonicalFile
        if (staging.parentFile != prefixRoot || !staging.mkdir()) {
            throw IOException("Could not create a private Wine prefix staging directory.")
        }

        return WinePrefixCreationTicket(
            capsuleId = capsuleId,
            stagingDirectory = staging,
            guestPrefixPath = toGuestPath(root, staging),
            guestDriveCPath = GUEST_MOUNT_ROOT + "/" + capsuleId + "/drive_c",
            guestDriveDPath = GUEST_MOUNT_ROOT + "/" + capsuleId + "/drive_d"
        )
    }

    /**
     * The caller must run the actual Wine loader with WINEPREFIX set to the
     * ticket's guestPrefixPath and execute wineboot -u before calling this.
     * The prefix becomes visible only after real registry and drive-link checks.
     */
    fun finalizeCreation(
        ticket: WinePrefixCreationTicket,
        winebootExitCode: Int,
        wineVersionOutput: String
    ): WinePrefixInfo {
        if (winebootExitCode != 0) {
            throw IOException("Wine prefix initialization failed with exit code " + winebootExitCode + ".")
        }
        val wineVersion = parseSupportedWineVersion(wineVersionOutput)
        val root = requireRootFs()
        val prefixRoot = ensureSafeDirectory(root, listOf("opt", "vyrox", "prefixes"))
        val staging = resolveStagingTicket(root, prefixRoot, ticket)

        validateWineRegistry(staging)
        validateWinebootDefaultLinks(staging)
        redirectCapsuleDrives(staging, ticket)

        val createdAt = System.currentTimeMillis()
        writeMetadata(
            staging = staging,
            capsuleId = ticket.capsuleId,
            wineVersion = wineVersion,
            createdAtEpochMillis = createdAt,
            driveCGuestPath = ticket.guestDriveCPath,
            driveDGuestPath = ticket.guestDriveDPath
        )

        val destination = File(prefixRoot, ticket.capsuleId).canonicalFile
        if (
            destination.parentFile != prefixRoot ||
            Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("The destination Wine prefix already exists.")
        }
        try {
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: Exception) {
            throw IOException("Could not atomically activate the Wine prefix.", exception)
        }

        return inspect(ticket.capsuleId)
            ?: run {
                destination.deleteRecursively()
                throw IOException("The finalized Wine prefix failed its integrity check.")
            }
    }

    fun inspect(capsuleId: String): WinePrefixInfo? = runCatching {
        requireValidCapsuleId(capsuleId)
        val root = requireRootFs()
        val prefixRoot = existingSafeDirectory(root, listOf("opt", "vyrox", "prefixes"))
            ?: return null
        val directory = File(prefixRoot, capsuleId).canonicalFile
        if (
            directory.parentFile != prefixRoot ||
            !directory.isDirectory ||
            Files.isSymbolicLink(directory.toPath())
        ) return null

        val metadataFile = File(directory, PREFIX_METADATA_FILE)
        if (Files.isSymbolicLink(metadataFile.toPath()) || !metadataFile.isFile) return null
        val metadata = Properties()
        metadataFile.inputStream().buffered().use { metadata.load(it) }

        val version = metadata.getProperty("wineVersion") ?: return null
        if (!isSupportedWineVersion(version)) return null
        if (
            metadata.getProperty("format") != PREFIX_FORMAT ||
            metadata.getProperty("capsuleId") != capsuleId ||
            metadata.getProperty("architecture") != PREFIX_ARCHITECTURE ||
            metadata.getProperty("wow64Mode") != PREFIX_WOW64_MODE
        ) return null

        validateWineRegistry(directory)
        val dosDevices = safeDosDevicesDirectory(directory) ?: return null
        val cLink = File(dosDevices, "c:")
        val dLink = File(dosDevices, "d:")
        val zLink = File(dosDevices, "z:")
        if (
            !Files.isSymbolicLink(cLink.toPath()) ||
            !Files.isSymbolicLink(dLink.toPath()) ||
            !Files.isSymbolicLink(zLink.toPath())
        ) return null

        val driveC = GUEST_MOUNT_ROOT + "/" + capsuleId + "/drive_c"
        val driveD = GUEST_MOUNT_ROOT + "/" + capsuleId + "/drive_d"
        if (
            readLink(cLink) != driveC ||
            readLink(dLink) != driveD ||
            readLink(zLink) != "/" ||
            metadata.getProperty("driveCGuestPath") != driveC ||
            metadata.getProperty("driveDGuestPath") != driveD
        ) return null

        val createdAt = metadata.getProperty("createdAtEpochMillis")?.toLongOrNull()
            ?.takeIf { it > 0L } ?: return null

        WinePrefixInfo(
            capsuleId = capsuleId,
            directory = directory,
            wineVersion = version,
            architecture = PREFIX_ARCHITECTURE,
            createdAtEpochMillis = createdAt,
            guestDriveCPath = driveC,
            guestDriveDPath = driveD
        )
    }.getOrNull()

    fun abortCreation(ticket: WinePrefixCreationTicket): Boolean = runCatching {
        val root = requireRootFs()
        val prefixRoot = ensureSafeDirectory(root, listOf("opt", "vyrox", "prefixes"))
        resolveStagingTicket(root, prefixRoot, ticket).deleteRecursively()
    }.getOrDefault(false)

    private fun resolveStagingTicket(
        root: File,
        prefixRoot: File,
        ticket: WinePrefixCreationTicket
    ): File {
        requireValidCapsuleId(ticket.capsuleId)
        val staging = ticket.stagingDirectory.canonicalFile
        if (
            staging.parentFile != prefixRoot ||
            !staging.name.startsWith(".staging-" + ticket.capsuleId + "-") ||
            ticket.guestPrefixPath != toGuestPath(root, staging) ||
            ticket.guestDriveCPath != GUEST_MOUNT_ROOT + "/" + ticket.capsuleId + "/drive_c" ||
            ticket.guestDriveDPath != GUEST_MOUNT_ROOT + "/" + ticket.capsuleId + "/drive_d" ||
            Files.isSymbolicLink(staging.toPath()) ||
            !staging.isDirectory
        ) {
            throw SecurityException("Wine prefix staging ticket is invalid or escaped its RootFS.")
        }
        return staging
    }

    private fun validateWineRegistry(prefix: File) {
        if (!prefix.isDirectory || Files.isSymbolicLink(prefix.toPath())) {
            throw IOException("Wine did not create a real prefix directory.")
        }
        for (registryName in listOf("system.reg", "user.reg")) {
            val registry = File(prefix, registryName)
            if (
                Files.isSymbolicLink(registry.toPath()) ||
                !registry.isFile ||
                registry.length() == 0L
            ) {
                throw IOException("Wineboot did not create the real " + registryName + " registry file.")
            }
            val firstLine = registry.bufferedReader().use { it.readLine().orEmpty() }
            if (!firstLine.startsWith(WINE_REGISTRY_HEADER)) {
                throw IOException(registryName + " does not have a Wine registry header.")
            }
        }
        val driveC = File(prefix, "drive_c")
        if (!driveC.isDirectory || Files.isSymbolicLink(driveC.toPath())) {
            throw IOException("Wineboot did not create its drive_c directory.")
        }
        if (safeDosDevicesDirectory(prefix) == null) {
            throw IOException("Wineboot did not create a safe dosdevices directory.")
        }
    }

    private fun validateWinebootDefaultLinks(prefix: File) {
        val dosDevices = safeDosDevicesDirectory(prefix)
            ?: throw IOException("Wine dosdevices directory is missing.")
        val cLink = File(dosDevices, "c:")
        val zLink = File(dosDevices, "z:")
        if (
            !Files.isSymbolicLink(cLink.toPath()) ||
            readLink(cLink) != "../drive_c"
        ) {
            throw IOException("Wineboot's default C: drive link is missing or unexpected.")
        }
        if (
            !Files.isSymbolicLink(zLink.toPath()) ||
            readLink(zLink) != "/"
        ) {
            throw IOException("Wineboot's default Z: drive link is missing or unexpected.")
        }
    }

    private fun redirectCapsuleDrives(
        prefix: File,
        ticket: WinePrefixCreationTicket
    ) {
        validateGuestMountPath(ticket.guestDriveCPath, ticket.capsuleId, "drive_c")
        validateGuestMountPath(ticket.guestDriveDPath, ticket.capsuleId, "drive_d")
        val dosDevices = safeDosDevicesDirectory(prefix)
            ?: throw IOException("Wine dosdevices directory is missing.")
        val cLink = File(dosDevices, "c:")
        val dLink = File(dosDevices, "d:")

        Files.delete(cLink.toPath())
        Files.createSymbolicLink(cLink.toPath(), Paths.get(ticket.guestDriveCPath))
        if (Files.exists(dLink.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isSymbolicLink(dLink.toPath())) {
                throw IOException("Wine's existing D: mapping is not a symbolic link.")
            }
            Files.delete(dLink.toPath())
        }
        Files.createSymbolicLink(dLink.toPath(), Paths.get(ticket.guestDriveDPath))
    }

    private fun writeMetadata(
        staging: File,
        capsuleId: String,
        wineVersion: String,
        createdAtEpochMillis: Long,
        driveCGuestPath: String,
        driveDGuestPath: String
    ) {
        val target = File(staging, PREFIX_METADATA_FILE)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Wine prefix metadata path already exists.")
        }
        val metadata = Properties().apply {
            setProperty("format", PREFIX_FORMAT)
            setProperty("capsuleId", capsuleId)
            setProperty("wineVersion", wineVersion)
            setProperty("architecture", PREFIX_ARCHITECTURE)
            setProperty("wow64Mode", PREFIX_WOW64_MODE)
            setProperty("createdAtEpochMillis", createdAtEpochMillis.toString())
            setProperty("driveCGuestPath", driveCGuestPath)
            setProperty("driveDGuestPath", driveDGuestPath)
        }
        val temp = File(staging, "." + PREFIX_METADATA_FILE + "-" + UUID.randomUUID() + ".tmp")
        try {
            temp.outputStream().buffered().use {
                metadata.store(it, "VYROX verified Wine prefix")
            }
            if (!temp.renameTo(target)) {
                throw IOException("Could not write Wine prefix metadata.")
            }
        } finally {
            temp.delete()
        }
    }

    private fun parseSupportedWineVersion(output: String): String {
        val version = WINE_VERSION_PATTERN.find(output)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("A real Wine version string is required.")
        require(isSupportedWineVersion(version)) {
            "Wine " + MINIMUM_WINE_MAJOR + " or newer is required for the new WoW64 prefix mode."
        }
        return version
    }

    private fun isSupportedWineVersion(version: String): Boolean {
        if (!WINE_VERSION_NUMBER.matches(version)) return false
        return version.substringBefore('.').toIntOrNull()?.let {
            it >= MINIMUM_WINE_MAJOR
        } == true
    }

    private fun safeDosDevicesDirectory(prefix: File): File? {
        val path = File(prefix, "dosdevices")
        if (
            Files.isSymbolicLink(path.toPath()) ||
            !path.isDirectory ||
            path.canonicalFile.parentFile != prefix.canonicalFile
        ) return null
        return path.canonicalFile
    }

    private fun validateGuestMountPath(path: String, capsuleId: String, drive: String) {
        require(path == GUEST_MOUNT_ROOT + "/" + capsuleId + "/" + drive) {
            "Wine prefix drive mapping must remain inside this Capsule's guest mount."
        }
    }

    private fun readLink(file: File): String = Files.readSymbolicLink(file.toPath()).toString()

    private fun ensureSafeDirectory(root: File, segments: List<String>): File {
        var current = root.canonicalFile
        for (segment in segments) {
            require(segment.matches(SAFE_SEGMENT)) { "Wine prefix path segment is invalid." }
            val next = File(current, segment)
            if (Files.isSymbolicLink(next.toPath())) {
                throw SecurityException("Wine prefix path contains a symbolic link: " + segment)
            }
            if (!next.exists() && !next.mkdir()) {
                throw IOException("Could not create Wine prefix storage directory: " + segment)
            }
            if (!next.isDirectory || next.canonicalFile.parentFile != current) {
                throw SecurityException("Wine prefix path escaped the selected RootFS.")
            }
            current = next.canonicalFile
        }
        return current
    }

    private fun existingSafeDirectory(root: File, segments: List<String>): File? {
        var current = root.canonicalFile
        for (segment in segments) {
            val next = File(current, segment)
            if (
                Files.isSymbolicLink(next.toPath()) ||
                !next.isDirectory ||
                next.canonicalFile.parentFile != current
            ) return null
            current = next.canonicalFile
        }
        return current
    }

    private fun requireRootFs(): File {
        if (
            Files.isSymbolicLink(rootFsDirectory.toPath()) ||
            !rootFsDirectory.isDirectory ||
            !File(rootFsDirectory, "etc/os-release").isFile
        ) {
            throw IOException("The selected Linux RootFS is missing or unsafe.")
        }
        return rootFsDirectory.canonicalFile
    }

    private fun toGuestPath(root: File, target: File): String {
        val relative = root.canonicalFile.toPath().relativize(target.canonicalFile.toPath())
        if (relative.startsWith("..")) {
            throw SecurityException("Wine prefix staging directory escaped the Linux RootFS.")
        }
        return "/" + relative.toString().replace(File.separatorChar, '/')
    }

    private fun requireValidCapsuleId(capsuleId: String) {
        require(CAPSULE_ID_PATTERN.matches(capsuleId)) { "Capsule identifier is invalid." }
    }

    companion object {
        const val PREFIX_FORMAT = "VYROX-WINE-PREFIX-1"
        const val PREFIX_METADATA_FILE = ".vyrox-prefix.properties"
        const val PREFIX_ARCHITECTURE = "win64"
        const val PREFIX_WOW64_MODE = "new-wow64"
        const val GUEST_MOUNT_ROOT = "/mnt/vyrox/capsules"
        const val WINE_REGISTRY_HEADER = "WINE REGISTRY Version"
        const val MINIMUM_WINE_MAJOR = 11

        private val CAPSULE_ID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )
        private val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]{1,80}")
        private val WINE_VERSION_PATTERN = Regex(
            "(?i)wine-(\\d+\\.\\d+(?:\\.\\d+)?)"
        )
        private val WINE_VERSION_NUMBER = Regex("\\d+\\.\\d+(?:\\.\\d+)?")
    }
}
