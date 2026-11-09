package com.vyrox.engine

import java.io.File
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
        require(File(rootFs, "bin/sh").isFile || File(rootFs, "usr/bin/sh").isFile) {
            "The selected RootFS does not contain a shell."
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
        val normalized = Paths.get(path).normalize()
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
