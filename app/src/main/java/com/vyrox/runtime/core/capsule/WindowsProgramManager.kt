package com.vyrox.runtime.core.capsule

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale

data class InstalledWindowsProgram(
    val id: String,
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val iconFile: File?
)

/**
 * Discovers user-installed Windows executables in the selected Capsule.
 * It deliberately ignores Windows system folders and never follows symlinks.
 */
class WindowsProgramManager(
    private val capsuleStorage: CapsuleStorage
) {
    fun discover(capsuleId: String, maximumResults: Int = MAX_RESULTS): List<InstalledWindowsProgram> {
        require(maximumResults in 1..MAX_RESULTS)
        val driveC = capsuleStorage.getDriveRoot(capsuleId, 'C') ?: return emptyList()
        val canonicalDrive = driveC.canonicalFile
        val iconDirectory = File(canonicalDrive, ICON_DIRECTORY)
            .takeIf { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
            ?.canonicalFile
            ?.takeIf { it.parentFile == canonicalDrive || it.path.startsWith(canonicalDrive.path + File.separator) }

        val roots = linkedSetOf<File>()
        listOf("Program Files", "Program Files (x86)", "Games").forEach { relative ->
            resolveDirectory(canonicalDrive, relative)?.let(roots::add)
        }
        findDirectory(canonicalDrive, "Users")?.let { usersDirectory ->
            usersDirectory.listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .take(MAX_USER_PROFILES)
                .forEach { userDirectory ->
                    listOf(
                        "AppData/Local/Programs",
                        "AppData/Roaming/Programs",
                        "AppData/Roaming/Microsoft/Windows/Start Menu/Programs",
                        "Desktop",
                        "Downloads"
                    ).forEach { relative ->
                        resolveDirectory(userDirectory, relative)?.let(roots::add)
                    }
                }
        }

        val discovered = linkedMapOf<String, File>()
        val pending = ArrayDeque<Pair<File, Int>>()
        roots.forEach { pending.addLast(it to 0) }
        var visitedDirectories = 0

        while (pending.isNotEmpty() &&
            discovered.size < maximumResults &&
            visitedDirectories < MAX_VISITED_DIRECTORIES
        ) {
            val (directory, depth) = pending.removeFirst()
            if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) continue
            visitedDirectories += 1
            val children = directory.listFiles()
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                .orEmpty()
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory && depth < MAX_DEPTH) {
                    pending.addLast(child to depth + 1)
                } else if (
                    child.isFile &&
                    child.name.endsWith(".exe", ignoreCase = true) &&
                    isLikelyApplication(child.name)
                ) {
                    val canonical = child.canonicalFile
                    if (
                        canonical.path.startsWith(canonicalDrive.path + File.separator) &&
                        canonical.parentFile?.isDirectory == true
                    ) {
                        discovered.putIfAbsent(canonical.path.lowercase(Locale.ROOT), canonical)
                    }
                    if (discovered.size >= maximumResults) break
                }
            }
        }

        return discovered.values.map { executable ->
            val relativePath = executable.relativeTo(canonicalDrive).invariantSeparatorsPath
            val icon = findIcon(iconDirectory, executable.name)
            InstalledWindowsProgram(
                id = stableId(relativePath),
                name = executable.nameWithoutExtension,
                relativePath = relativePath,
                sizeBytes = executable.length(),
                iconFile = icon
            )
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun findIcon(directory: File?, executableName: String): File? {
        if (directory == null) return null
        val prefix = executableName + "_14_"
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.startsWith(prefix, true) }
            .filter { it.extension.equals("png", true) }
            .sortedByDescending { it.name.contains("48x48", ignoreCase = true) }
            .firstOrNull()
            ?.canonicalFile
            ?.takeIf { it.path.startsWith(directory.canonicalPath + File.separator) }
    }

    private fun findDirectory(parent: File, name: String): File? {
        val canonicalParent = parent.canonicalFile
        return parent.listFiles()
            .orEmpty()
            .firstOrNull {
                it.name.equals(name, ignoreCase = true) &&
                    it.isDirectory &&
                    !Files.isSymbolicLink(it.toPath())
            }
            ?.canonicalFile
            ?.takeIf { it.parentFile == canonicalParent }
    }

    private fun resolveDirectory(root: File, relativePath: String): File? {
        var current = root
        for (segment in relativePath.split('/')) {
            current = findDirectory(current, segment) ?: return null
        }
        return current
    }

    private fun isLikelyApplication(fileName: String): Boolean {
        val name = fileName.lowercase(Locale.ROOT)
        return !name.startsWith("unins") &&
            !name.startsWith("uninstall") &&
            !name.startsWith("setup") &&
            !name.startsWith("update") &&
            !name.startsWith("crashpad") &&
            !name.startsWith("elevated") &&
            !name.endsWith("helper.exe") &&
            !name.endsWith("updater.exe")
    }

    private fun stableId(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(path.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_RESULTS = 500
        const val MAX_USER_PROFILES = 128
        const val MAX_DEPTH = 6
        const val MAX_VISITED_DIRECTORIES = 5_000
        const val ICON_DIRECTORY = "VYROX/Icons"
    }
}
