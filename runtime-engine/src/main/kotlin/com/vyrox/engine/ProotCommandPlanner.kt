package com.vyrox.engine

import java.io.File
import java.io.FileInputStream
import java.nio.file.Paths

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

/** Only ProotCommandPlanner can create a ProcessBuilder command plan. */
class ProotCommandPlan internal constructor(
    val executable: File,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val processWorkingDirectory: File
)

object ProotCommandPlanner {
    fun build(request: ProotLaunchRequest): ProotCommandPlan {
        val proot = request.prootExecutable.canonicalFile
        require(proot.isFile && proot.canExecute()) {
            "A built, executable Android PRoot binary is not installed."
        }
        require(isInsideAnyAllowedRoot(proot, request.allowedHostRoots)) {
            "PRoot binary is outside the app-approved engine directories."
        }
        require(readElfMachine(proot) == ARM64_ELF_MACHINE) {
            "PRoot must be a 64-bit AArch64 Android ELF executable."
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

        val shell = sequenceOf(File(rootFs, "bin/sh"), File(rootFs, "usr/bin/sh"))
            .firstOrNull { it.isFile }
            ?: throw IllegalArgumentException("The selected RootFS does not contain a shell.")
        require(readElfMachine(shell) == ARM64_ELF_MACHINE) {
            "The selected RootFS shell is not a 64-bit AArch64 Linux ELF executable."
        }

        val command = resolveGuestPath(rootFs, request.guestCommand)
        require(command.isFile && command.canExecute()) {
            "The requested guest command is not an executable file in the RootFS."
        }
        require(readElfMachine(command) == ARM64_ELF_MACHINE) {
            "The requested guest command is not a 64-bit AArch64 Linux ELF executable."
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
            require(key in ALLOWED_ENVIRONMENT_KEYS) {
                "Environment variable is not allowed for a managed PRoot process: $key"
            }
            validateArgument(value)
            if (key in ABSOLUTE_GUEST_PATH_ENVIRONMENT_KEYS) {
                validateGuestPathList(value, key)
            }
        }

        val safeEnvironment = DEFAULT_ENVIRONMENT + request.environment
        val normalizedCommand = toGuestAbsolutePath(rootFs, command)
        val normalizedWorkingDirectory = toGuestAbsolutePath(rootFs, workingDirectory)

        val args = mutableListOf(
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "--rootfs=" + rootFs.absolutePath,
            "--cwd=" + normalizedWorkingDirectory
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

        args += normalizedCommand
        args += request.arguments

        return ProotCommandPlan(
            executable = proot,
            arguments = args.toList(),
            environment = safeEnvironment.toMap(),
            processWorkingDirectory = rootFs
        )
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

    private fun toGuestAbsolutePath(rootFs: File, target: File): String {
        val relative = rootFs.canonicalFile.toPath().relativize(target.canonicalFile.toPath())
        return "/" + relative.toString().replace(File.separatorChar, '/').removePrefix("/")
    }

    private fun validateGuestPath(path: String) {
        require(path.startsWith("/")) { "Guest paths must be absolute." }
        require(!path.contains('\u0000') && !path.contains(':') && !path.contains('\\')) {
            "Invalid guest path."
        }
        val normalized = Paths.get(path).normalize()
        require(!normalized.startsWith("..")) { "Guest path contains traversal." }
    }

    private fun validateGuestPathList(value: String, key: String) {
        require(value.isNotBlank()) { "$key cannot be empty." }
        value.split(':').forEach { path ->
            require(path.startsWith("/") && !path.contains("..")) {
                "$key must contain only absolute guest paths without traversal."
            }
        }
    }

    private fun validateArgument(value: String) {
        require(!value.contains('\u0000')) { "Arguments and environment values cannot contain NUL." }
        require(value.length <= MAX_ARGUMENT_LENGTH) { "A command argument is too long." }
    }

    private fun readElfMachine(file: File): Int? {
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
            header[3] != 'F'.code.toByte() ||
            header[4] != 2.toByte()
        ) return null

        val endian = header[5].toInt() and 0xFF
        val first = header[18].toInt() and 0xFF
        val second = header[19].toInt() and 0xFF
        return when (endian) {
            1 -> first or (second shl 8)
            2 -> (first shl 8) or second
            else -> null
        }
    }

    private fun isInsideAnyAllowedRoot(candidate: File, roots: List<File>): Boolean {
        val path = candidate.canonicalPath
        return roots.any { root ->
            val rootPath = root.canonicalPath
            path == rootPath || path.startsWith(rootPath + File.separator)
        }
    }

    private val ALLOWED_ENVIRONMENT_KEYS = setOf(
        "HOME", "LANG", "LC_ALL", "PATH", "TERM", "TZ", "USER", "LOGNAME", "SHELL"
    )
    private val ABSOLUTE_GUEST_PATH_ENVIRONMENT_KEYS = setOf("HOME", "PATH", "SHELL")
    private val DEFAULT_ENVIRONMENT = mapOf(
        "HOME" to "/root",
        "LANG" to "C.UTF-8",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM" to "xterm-256color"
    )

    private const val ARM64_ELF_MACHINE = 183
    private const val MAX_ARGUMENTS = 256
    private const val MAX_ENVIRONMENT_ITEMS = 128
    private const val MAX_ARGUMENT_LENGTH = 8192
}
