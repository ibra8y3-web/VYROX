package com.vyrox.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeProcessSupervisorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun capturesExitCodeAndCombinedOutput() {
        val working = temporaryFolder.newFolder("work")
        val logs = temporaryFolder.newFolder("logs")
        val plan = shellPlan(working, listOf("-c", "printf 'VYROX_STDOUT'; printf 'VYROX_STDERR' >&2"))

        val process = RuntimeProcessSupervisor(logs, listOf(temporaryFolder.root, File("/usr"))).start(plan, "successful-run")
        val result = process.await(5_000)

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        val content = result.logFile.readText()
        assertTrue(content.contains("VYROX_STDOUT"))
        assertTrue(content.contains("VYROX_STDERR"))
    }

    @Test
    fun terminatesProcessWhenTimeoutIsReached() {
        val working = temporaryFolder.newFolder("timeout-work")
        val logs = temporaryFolder.newFolder("timeout-logs")
        val process = RuntimeProcessSupervisor(logs, listOf(temporaryFolder.root, File("/usr"))).start(
            shellPlan(working, listOf("-c", "sleep 5")),
            "timeout-run"
        )

        val result = process.await(100)

        assertTrue(result.timedOut)
        assertFalse(result.exitCode == 0)
    }

    @Test
    fun capsRuntimeLogSize() {
        val working = temporaryFolder.newFolder("large-work")
        val logs = temporaryFolder.newFolder("large-logs")
        val process = RuntimeProcessSupervisor(logs, listOf(temporaryFolder.root, File("/usr"))).start(
            shellPlan(working, listOf("-c", "yes VYROX_RUNTIME_LOG")),
            "large-log-run",
            maxLogBytes = 1024
        )

        val result = process.await(200)

        assertTrue(result.logTruncated)
        assertTrue(result.logFile.length() <= 1_024L)
    }

    @Test
    fun setsHostLibraryPathFromApprovedNativeLibraryDirectory() {
        val working = temporaryFolder.newFolder("host-loader-work")
        val logs = temporaryFolder.newFolder("host-loader-logs")
        val process = RuntimeProcessSupervisor(
            logs,
            listOf(temporaryFolder.root, File("/usr"))
        ).start(
            shellPlan(working, listOf("-c", "printf '%s' \"\$LD_LIBRARY_PATH\"")),
            "host-loader"
        )
        val result = process.await(5_000)
        assertEquals(0, result.exitCode)
        assertTrue(result.logFile.readText().contains(File("/usr/lib").canonicalPath))
    }

    @Test
    fun rejectsGuestAttemptsToOverrideDynamicLinkerVariables() {
        val working = temporaryFolder.newFolder("host-env-work")
        val logs = temporaryFolder.newFolder("host-env-logs")
        val plan = shellPlan(working, listOf("-c", "true")).copy(
            environment = mapOf("LD_PRELOAD" to "untrusted.so")
        )
        val supervisor = RuntimeProcessSupervisor(logs, listOf(temporaryFolder.root, File("/usr")))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            supervisor.start(plan, "host-env")
        }
        assertFalse(File(logs, "host-env.log").exists())
    }

    @Test
    fun refusesExecutableOutsideApprovedRoots() {
        val working = temporaryFolder.newFolder("approved-work")
        val logs = File(working, "logs").apply { mkdirs() }
        val supervisor = RuntimeProcessSupervisor(logs, listOf(working, File("/usr/lib")))
        val exception = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            supervisor.start(shellPlan(working, listOf("-c", "true")), "outside-executable")
        }
        assertTrue(exception.message.orEmpty().contains("outside"))
    }

    @Test
    fun rejectsNulArgumentBeforeCreatingLogFile() {
        val working = temporaryFolder.newFolder("invalid-argument-work")
        val logs = temporaryFolder.newFolder("invalid-argument-logs")
        val supervisor = RuntimeProcessSupervisor(logs, listOf(temporaryFolder.root, File("/usr")))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            supervisor.start(shellPlan(working, listOf("-c", "bad\u0000argument")), "invalid-argument")
        }
        assertFalse(File(logs, "invalid-argument.log").exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidRunId() {
        val working = temporaryFolder.newFolder("bad-work")
        val logs = temporaryFolder.newFolder("bad-logs")
        RuntimeProcessSupervisor(logs, listOf(temporaryFolder.root, File("/usr"))).start(
            shellPlan(working, listOf("-c", "true")),
            "../bad-run"
        )
    }

    private fun shellPlan(working: File, arguments: List<String>) = ProotCommandPlan(
        executable = File("/bin/sh"),
        arguments = arguments,
        environment = emptyMap(),
        processWorkingDirectory = working,
        nativeLibraryRoot = File("/usr/lib")
    )
}
