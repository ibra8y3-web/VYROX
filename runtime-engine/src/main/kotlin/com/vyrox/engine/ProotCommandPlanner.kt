package com.vyrox.engine

import java.io.File
import java.nio.file.Paths
import java.util.Properties

data class ProotBindMount(
    val hostSource: File,
    val guestTarget: String
)

data class ProotLaunchRequest(
    val prootExecutable: File,
    val rootFsDirectory: File,
    val rootFsArchitecture: LinuxRootFsArchitecture,
    val guestCommand: String,
    val guestWorkingDirectory: String = "/",
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val bindMounts: List<ProotBindMount> = emptyList(),
    val allowedHostRoots: List<File>
)

data class ProotCommandPlan(
    val executable: File,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val processWorkingDirectory: File
)

/**
 * Builds a ProcessBuilder-ready PRoot argument vector.
 * It does not execute PRoot or claim that the Android binary is installed.
 */
object ProotCommandPlanner {
    fun build(request: ProotLaunchRequest): ProotCommandPlan {
        val proot = request.prootExecutable.canonicalFile
        require(proot.isFile && proot.canExecute()) {
            "A built, executable Android PRoot binary is not installed."
        }
        require(isInsideAnyAllowedRoot(proot, request.allowedHostRoots)) {
            "PRoot binary is outside the app-approved engine directories."
        }

        val rootFs = request.rootFsDirectory.canonicalFile
        require(rootFs.isDirectory && File(rootFs, "etc/os-release").isFile) {
            "The selected Linux RootFS is missing or invalid."
        }
        require(isInsideAnyAllowedRoot(rootFs, request.allowedHostRoots)) {
            "Linux RootFS is outside the app-approved storage directories."
        }
        require(request.rootFsArchitecture == LinuxRootFsArchitecture.ARM64) {
            "The initial Android PRoot host must use an ARM64 RootFS."
        }
        validateImportedRootFsMetadata(rootFs, request.rootFsArchitecture)
        val shell = sequenceOf(File(rootFs, "bin/sh"), File(rootFs, "usr/bin/sh"))
            .firstOrNull { it.isFile }
            ?: throw IllegalArgumentException("The selected RootFS does not contain a shell.")
        require(isArm64LinuxElf64(shell)) {
            "The initial Android RootFS must contain a 64-bit little-endian AArch64 shell."
        }

        val command = resolveGuestPath(rootFs, request.guestCommand)
        require(command.isFile && command.canExecute()) {
            "The requested guest command is not an executable file in the RootFS."
        }
        val workingDirectory = resolveGuestPath(rootFs, request.guestWorkingDirectory)
        require(workingDirectory.isDirectory) {
            "The requested guest working directory does not exist."
        }

        require(request.arguments.size <= MAX_ARGUMENTS) {
            "The guest command contains too many arguments."
        }
        request.arguments.forEach(::validateArgument)

        require(request.environment.size <= MAX_ENVIRONMENT_ITEMS) {
            "Too many guest environment variables were provided."
        }
        request.environment.forEach { (key, value) ->
            require(ENVIRONMENT_KEY.matches(key)) { "Invalid environment variable name." }
            validateArgument(value)
        }

        val args = mutableListOf(
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "--rootfs=" + rootFs.absolutePath,
            "--cwd=" + request.guestWorkingDirectory
        )

        request.bindMounts.forEach { bind ->
            val source = bind.hostSource.canonicalFile
            require(source.exists()) { "A requested host bind source does not exist." }
            require(isInsideAnyAllowedRoot(source, request.allowedHostRoots)) {
                "A requested host bind source is outside the app-approved directories."
            }
            validateGuestPath(bind.guestTarget)
            require(!source.path.contains(':')) {
                "Host bind paths containing ':' are not supported."
            }
            args += "--bind=" + source.absolutePath + ":" + bind.guestTarget
        }

        args += request.guestCommand
        args += request.arguments

        return ProotCommandPlan(
            executable = proot,
            arguments = args,
            environment = request.environment.toMap(),
            processWorkingDirectory = rootFs
        )
    }

    private fun validateImportedRootFsMetadata(
        rootFs: File,
        expectedArchitecture: LinuxRootFsArchitecture
    ) {
        val metadataFile = File(rootFs, ".vyrox-rootfs.properties")
        require(metadataFile.isFile) {
            "The selected RootFS was not imported by the VYROX RootFS manager."
        }
        val metadata = Properties()
        metadataFile.inputStream().buffered().use { metadata.load(it) }
        require(metadata.getProperty("architecture") == expectedArchitecture.name) {
            "RootFS metadata architecture does not match the selected image."
        }
        require(metadata.getProperty("archiveSha256").orEmpty().matches(Regex("[0-9a-fA-F]{64}"))) {
            "The selected RootFS metadata has no valid archive digest."
        }
    }

    private fun isArm64LinuxElf64(file: File): Boolean {
        val header = ByteArray(20)
        file.inputStream().use { input ->
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
        val encoding = header[5].toInt() and 0xFF
        val version = header[6].toInt() and 0xFF
        val machine = (header[18].toInt() and 0xFF) or
            ((header[19].toInt() and 0xFF) shl 8)
        return validMagic && elfClass == 2 && encoding == 1 && version == 1 && machine == 183
    }

    private fun resolveGuestPath(rootFs: File, guestPath: String): File {
        validateGuestPath(guestPath)
        val normalized = Paths.get(guestPath).normalize()
        require(normalized.isAbsolute) { "Guest paths must be absolute." }
        require(!normalized.startsWith("..")) { "Guest path escapes the RootFS." }

        val relative = normalized.toString().removePrefix(File.separator)
        val root = rootFs.canonicalFile
        val target = File(root, relative).canonicalFile
        require(target == root || target.path.startsWith(root.path + File.separator)) {
            "Guest path escapes the RootFS."
        }
        return target
    }

    private fun validateGuestPath(path: String) {
        require(path.startsWith("/")) { "Guest paths must be absolute." }
        require(!path.contains('\u0000') && !path.contains(':') && !path.contains('\\')) {
            "Invalid guest path."
        }
        val pathObject = Paths.get(path)
        require(pathObject.none { it.toString() == ".." || it.toString() == "." }) {
            "Guest path contains traversal or dot segments."
        }
        val normalized = pathObject.normalize()
        require(!normalized.startsWith("..")) { "Guest path contains traversal." }
    }

    private fun validateArgument(value: String) {
        require(!value.contains('\u0000')) { "Arguments and environment values cannot contain NUL." }
        require(value.length <= MAX_ARGUMENT_LENGTH) { "A command argument is too long." }
    }

    private fun isInsideAnyAllowedRoot(candidate: File, roots: List<File>): Boolean {
        val path = candidate.canonicalPath
        return roots.any { root ->
            val rootPath = root.canonicalPath
            path == rootPath || path.startsWith(rootPath + File.separator)
        }
    }

    private val ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private const val MAX_ARGUMENTS = 256
    private const val MAX_ENVIRONMENT_ITEMS = 128
    private const val MAX_ARGUMENT_LENGTH = 8192
}
