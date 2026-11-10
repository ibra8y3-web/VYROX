package com.vyrox.engine

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class RuntimeProcessResult(
    val exitCode: Int?,
    val timedOut: Boolean,
    val logFile: File,
    val logTruncated: Boolean,
    val durationMillis: Long
)

class ManagedRuntimeProcess internal constructor(
    val runId: String,
    private val process: Process,
    private val logThread: Thread,
    private val truncated: AtomicBoolean,
    val logFile: File,
    private val temporaryDirectory: File,
    private val startedAtMillis: Long
) {
    fun await(timeoutMillis: Long): RuntimeProcessResult {
        require(timeoutMillis in 1..RuntimeProcessSupervisor.MAX_TIMEOUT_MILLIS) {
            "Runtime timeout must be between 1 ms and 4 hours."
        }
        val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!completed) terminateProcess()
        logThread.join(LOG_THREAD_JOIN_MILLIS)

        val exitCode = if (process.isAlive) null else process.exitValue()
        if (exitCode != null) temporaryDirectory.deleteRecursively()
        return RuntimeProcessResult(
            exitCode = exitCode,
            timedOut = !completed,
            logFile = logFile,
            logTruncated = truncated.get(),
            durationMillis = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L)
        )
    }

    val isRunning: Boolean
        get() = process.isAlive

    fun terminate() {
        terminateProcess()
        logThread.join(LOG_THREAD_JOIN_MILLIS)
        if (!process.isAlive) temporaryDirectory.deleteRecursively()
    }

    private fun terminateProcess() {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(TERMINATE_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(FORCE_TERMINATE_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        const val LOG_THREAD_JOIN_MILLIS = 5_000L
        const val TERMINATE_GRACE_MILLIS = 500L
        const val FORCE_TERMINATE_WAIT_MILLIS = 2_000L
    }
}

/**
 * Starts a prevalidated PRoot command and captures combined stdout/stderr.
 *
 * This supervisor is not a security sandbox. It only accepts an executable,
 * working directory and log directory below caller-approved storage roots.
 */
class RuntimeProcessSupervisor(
    private val logDirectory: File,
    private val allowedHostRoots: List<File>
) {
    @Throws(IOException::class)
    fun start(
        plan: ProotCommandPlan,
        runId: String,
        maxLogBytes: Long = DEFAULT_MAX_LOG_BYTES
    ): ManagedRuntimeProcess {
        require(RUN_ID_PATTERN.matches(runId)) { "Invalid runtime run identifier." }
        require(maxLogBytes in 1..MAX_LOG_BYTES) {
            "Runtime log size must be between 1 byte and 10 MiB."
        }
        require(allowedHostRoots.isNotEmpty()) {
            "At least one approved app-private root is required."
        }
        require(plan.arguments.size <= MAX_ARGUMENTS) { "Too many runtime arguments." }
        plan.arguments.forEach(::validateProcessArgument)
        require(plan.environment.size <= MAX_ENVIRONMENT_ITEMS) {
            "Too many runtime environment variables."
        }
        plan.environment.forEach { (key, value) ->
            require(ENVIRONMENT_KEY.matches(key)) { "Invalid runtime environment variable name." }
            require(!key.startsWith("LD_") && key != "PROOT_LOADER" && key != "TMPDIR") {
                "Dynamic-linker, PRoot loader, and host temporary-directory variables are controlled by the host runtime, not guest input."
            }
            validateProcessArgument(value)
        }

        val executable = plan.executable.canonicalFile
        if (!executable.isFile || !executable.canExecute()) {
            throw IOException("The runtime executable is missing or is not executable.")
        }
        require(isInsideApprovedRoot(executable)) {
            "The runtime executable is outside the app-approved directories."
        }
        val hostLibraryDirectory = plan.nativeLibraryRoot.canonicalFile
        require(hostLibraryDirectory.isDirectory && isInsideApprovedRoot(hostLibraryDirectory)) {
            "The host native-library directory is outside the app-approved directories."
        }
        val guestLoaderPath = File(hostLibraryDirectory, "libproot-loader.so")
        if (Files.isSymbolicLink(guestLoaderPath.toPath())) {
            throw IOException("The PRoot guest loader cannot be a symbolic link.")
        }
        val guestLoader = guestLoaderPath.canonicalFile
        if (
            !guestLoader.isFile ||
            !guestLoader.canExecute() ||
            guestLoader.parentFile != hostLibraryDirectory
        ) {
            throw IOException("The Android-packaged PRoot guest loader is missing or not executable.")
        }
        val workingDirectory = plan.processWorkingDirectory.canonicalFile
        if (!workingDirectory.isDirectory) {
            throw IOException("The runtime working directory does not exist.")
        }
        require(isInsideApprovedRoot(workingDirectory)) {
            "The runtime working directory is outside the app-approved directories."
        }

        if (!logDirectory.exists() && !logDirectory.mkdirs()) {
            throw IOException("Could not create the runtime log directory.")
        }
        val canonicalLogDirectory = logDirectory.canonicalFile
        require(isInsideApprovedRoot(canonicalLogDirectory)) {
            "The runtime log directory is outside the app-approved directories."
        }
        val logFile = File(canonicalLogDirectory, "$runId.log").canonicalFile
        if (logFile.parentFile != canonicalLogDirectory || logFile.exists()) {
            throw IOException("The runtime log path is invalid or already exists.")
        }
        val temporaryDirectory = File(canonicalLogDirectory, ".tmp-$runId").canonicalFile
        if (
            temporaryDirectory.parentFile != canonicalLogDirectory ||
            temporaryDirectory.exists() ||
            Files.isSymbolicLink(temporaryDirectory.toPath())
        ) {
            throw IOException("The runtime temporary-directory path is invalid or already exists.")
        }

        val command = ArrayList<String>(plan.arguments.size + 1)
        command += executable.absolutePath
        command.addAll(plan.arguments)

        if (!logFile.createNewFile()) {
            throw IOException("Could not create the runtime log file.")
        }
        if (!temporaryDirectory.mkdir()) {
            logFile.delete()
            throw IOException("Could not create the private runtime temporary directory.")
        }

        try {
            val process = ProcessBuilder(command)
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    // Static talloc and libandroid-shmem are linked into PRoot.
                    // Only the external guest loader path is host-controlled.
                    environment().putAll(plan.environment)
                    environment()["TMPDIR"] = temporaryDirectory.absolutePath
                    environment()["PROOT_LOADER"] = guestLoader.absolutePath
                }
                .start()

            val truncated = AtomicBoolean(false)
            val collector = Thread(
                { captureOutput(process, logFile, maxLogBytes, truncated) },
                "vyrox-log-$runId"
            ).apply {
                isDaemon = true
                start()
            }
            return ManagedRuntimeProcess(
                runId = runId,
                process = process,
                logThread = collector,
                truncated = truncated,
                logFile = logFile,
                temporaryDirectory = temporaryDirectory,
                startedAtMillis = System.currentTimeMillis()
            )
        } catch (exception: Exception) {
            temporaryDirectory.deleteRecursively()
            logFile.delete()
            if (exception is IOException) throw exception
            throw IOException("Could not start the runtime process.", exception)
        }
    }

    private fun captureOutput(
        process: Process,
        logFile: File,
        maxBytes: Long,
        truncated: AtomicBoolean
    ) {
        var written = 0L
        val marker = LOG_TRUNCATION_MARKER.toByteArray()
        val contentLimit = (maxBytes - marker.size).coerceAtLeast(0L)
        try {
            process.inputStream.use { input ->
                FileOutputStream(logFile, true).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        val remaining = contentLimit - written
                        if (remaining > 0L) {
                            val toWrite = minOf(read.toLong(), remaining).toInt()
                            output.write(buffer, 0, toWrite)
                            written += toWrite
                        }
                        if (read.toLong() > remaining) truncated.set(true)
                    }
                    if (truncated.get()) {
                        val markerBytes = minOf(marker.size.toLong(), maxBytes - written).toInt()
                        if (markerBytes > 0) output.write(marker, 0, markerBytes)
                    }
                }
            }
        } catch (_: Exception) {
            // Log capture failures do not keep the child process alive.
        }
    }

    private fun validateProcessArgument(value: String) {
        require(!value.contains('\u0000')) { "Runtime arguments cannot contain NUL." }
        require(value.length <= MAX_ARGUMENT_LENGTH) { "A runtime argument is too long." }
    }

    private fun isInsideApprovedRoot(candidate: File): Boolean {
        val path = candidate.canonicalPath
        return allowedHostRoots.any { root ->
            if (!root.isDirectory) return@any false
            val rootPath = root.canonicalPath
            path == rootPath || path.startsWith(rootPath + File.separator)
        }
    }

    companion object {
        const val DEFAULT_MAX_LOG_BYTES: Long = 2L * 1024L * 1024L
        const val MAX_LOG_BYTES: Long = 10L * 1024L * 1024L
        const val MAX_TIMEOUT_MILLIS: Long = 4L * 60L * 60L * 1000L
        private const val MAX_ARGUMENTS = 256
        private const val MAX_ENVIRONMENT_ITEMS = 128
        private const val MAX_ARGUMENT_LENGTH = 8192
        private const val LOG_TRUNCATION_MARKER = "\n[VYROX] Runtime log truncated.\n"
        private val ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val RUN_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
