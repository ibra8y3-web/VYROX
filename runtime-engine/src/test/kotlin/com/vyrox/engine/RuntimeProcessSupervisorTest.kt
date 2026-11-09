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

        val process = RuntimeProcessSupervisor(logs).start(plan, "successful-run")
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
        val process = RuntimeProcessSupervisor(logs).start(
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
        val process = RuntimeProcessSupervisor(logs).start(
            shellPlan(working, listOf("-c", "yes VYROX_RUNTIME_LOG")),
            "large-log-run",
            maxLogBytes = 1024
        )

        val result = process.await(200)

        assertTrue(result.logTruncated)
        assertTrue(result.logFile.length() < 2_048L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidRunId() {
        val working = temporaryFolder.newFolder("bad-work")
        val logs = temporaryFolder.newFolder("bad-logs")
        RuntimeProcessSupervisor(logs).start(
            shellPlan(working, listOf("-c", "true")),
            "../bad-run"
        )
    }

    private fun shellPlan(working: File, arguments: List<String>) = ProotCommandPlan(
        executable = File("/bin/sh"),
        arguments = arguments,
        environment = emptyMap(),
        processWorkingDirectory = working
    )
}
