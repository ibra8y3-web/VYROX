package com.vyrox.engine

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    private val startedAtMillis: Long
) {
    fun await(timeoutMillis: Long): RuntimeProcessResult {
        require(timeoutMillis in 1..RuntimeProcessSupervisor.MAX_TIMEOUT_MILLIS) {
            "Runtime timeout must be between 1 ms and 1 hour."
        }
        val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!completed) terminateProcess()
        logThread.join(LOG_THREAD_JOIN_MILLIS)

        val exitCode = if (process.isAlive) null else process.exitValue()
        return RuntimeProcessResult(
            exitCode = exitCode,
            timedOut = !completed,
            logFile = logFile,
            logTruncated = truncated.get(),
            durationMillis = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L)
        )
    }

    fun terminate() {
        terminateProcess()
        logThread.join(LOG_THREAD_JOIN_MILLIS)
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
 * Starts a prevalidated ProotCommandPlan and captures combined stdout/stderr.
 *
 * This is a process supervisor, not a security sandbox. The caller must only
 * provide command plans created from trusted, app-private engine binaries.
 */
class RuntimeProcessSupervisor(private val logDirectory: File) {

    @Throws(IOException::class)
    fun start(
        plan: ProotCommandPlan,
        runId: String,
        maxLogBytes: Long = DEFAULT_MAX_LOG_BYTES
    ): ManagedRuntimeProcess {
        require(RUN_ID_PATTERN.matches(runId)) { "Invalid runtime run identifier." }
        require(maxLogBytes in 1..MAX_LOG_BYTES) { "Runtime log size must be between 1 byte and 10 MiB." }

        val executable = plan.executable.canonicalFile
        if (!executable.isFile || !executable.canExecute()) {
            throw IOException("The runtime executable is missing or is not executable.")
        }
        val workingDirectory = plan.processWorkingDirectory.canonicalFile
        if (!workingDirectory.isDirectory) {
            throw IOException("The runtime working directory does not exist.")
        }
        if (!logDirectory.exists() && !logDirectory.mkdirs()) {
            throw IOException("Could not create the runtime log directory.")
        }
        val canonicalLogDirectory = logDirectory.canonicalFile
        val logFile = File(canonicalLogDirectory, "$runId.log").canonicalFile
        if (logFile.parentFile != canonicalLogDirectory || logFile.exists()) {
            throw IOException("The runtime log path is invalid or already exists.")
        }
        if (!logFile.createNewFile()) {
            throw IOException("Could not create the runtime log file.")
        }

        val command = ArrayList<String>(plan.arguments.size + 1)
        command += executable.absolutePath
        plan.arguments.forEach { argument ->
            require(!argument.contains('\u0000')) { "Runtime arguments cannot contain NUL." }
            command += argument
        }

        try {
            val process = ProcessBuilder(command)
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .apply { environment().putAll(plan.environment) }
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
                startedAtMillis = System.currentTimeMillis()
            )
        } catch (exception: Exception) {
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
        try {
            process.inputStream.use { input ->
                FileOutputStream(logFile, true).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        val remaining = maxBytes - written
                        if (remaining > 0L) {
                            val toWrite = minOf(read.toLong(), remaining).toInt()
                            output.write(buffer, 0, toWrite)
                            written += toWrite
                        }
                        if (toWriteLessThan(read, remaining)) {
                            truncated.set(true)
                        }
                    }
                    if (truncated.get()) {
                        output.write("\n[VYROX] Runtime log reached its configured size limit.\n".toByteArray())
                    }
                }
            }
        } catch (_: Exception) {
            // Log capture failures do not keep the child process alive.
        }
    }

    private fun toWriteLessThan(read: Int, remaining: Long): Boolean =
        read.toLong() > remaining

    companion object {
        const val DEFAULT_MAX_LOG_BYTES: Long = 2L * 1024L * 1024L
        const val MAX_LOG_BYTES: Long = 10L * 1024L * 1024L
        const val MAX_TIMEOUT_MILLIS: Long = 60L * 60L * 1000L
        private val RUN_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
