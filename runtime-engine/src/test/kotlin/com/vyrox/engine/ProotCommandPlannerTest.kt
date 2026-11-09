package com.vyrox.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotCommandPlannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun buildsArgumentVectorWithoutShellConcatenation() {
        val root = temporaryFolder.newFolder("runtime")
        val capsule = File(root, "capsule").apply { mkdirs() }
        val rootFs = File(capsule, "rootfs").apply { mkdirs() }
        File(rootFs, "etc").mkdirs()
        File(rootFs, "bin").mkdirs()
        File(rootFs, "etc/os-release").writeText("ID=ubuntu")
        File(rootFs, "bin/sh").apply {
            writeText("#!/bin/sh")
            setExecutable(true)
        }
        val proot = File(root, "proot").apply {
            writeText("binary placeholder")
            setExecutable(true)
        }
        val driveC = File(capsule, "drive_c").apply { mkdirs() }

        val plan = ProotCommandPlanner.build(
            ProotLaunchRequest(
                prootExecutable = proot,
                rootFsDirectory = rootFs,
                rootFsArchitecture = LinuxRootFsArchitecture.ARM64,
                guestCommand = "/bin/sh",
                guestWorkingDirectory = "/",
                arguments = listOf("-c", "echo hello; echo there"),
                environment = mapOf("HOME" to "/root", "LANG" to "C.UTF-8"),
                bindMounts = listOf(ProotBindMount(driveC, "/mnt/vyrox/C")),
                allowedHostRoots = listOf(root)
            )
        )

        assertEquals(proot.canonicalFile, plan.executable)
        assertTrue(plan.arguments.contains("--rootfs=" + rootFs.canonicalPath))
        assertTrue(plan.arguments.contains("--bind=" + driveC.canonicalPath + ":/mnt/vyrox/C"))
        assertEquals("echo hello; echo there", plan.arguments.last())
        assertEquals("C.UTF-8", plan.environment["LANG"])
    }

    @Test
    fun rejectsMissingProotBinary() {
        val root = temporaryFolder.newFolder("root")
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(
                ProotLaunchRequest(
                    prootExecutable = File(root, "missing-proot"),
                    rootFsDirectory = root,
                    rootFsArchitecture = LinuxRootFsArchitecture.ARM64,
                    guestCommand = "/bin/sh",
                    allowedHostRoots = listOf(root)
                )
            )
        }
    }

    @Test
    fun rejectsGuestPathTraversalAndMissingCommand() {
        val (root, proot, rootFs) = createValidRuntime()
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(
                ProotLaunchRequest(
                    prootExecutable = proot,
                    rootFsDirectory = rootFs,
                    rootFsArchitecture = LinuxRootFsArchitecture.ARM64,
                    guestCommand = "/../../outside",
                    allowedHostRoots = listOf(root)
                )
            )
        }
    }

    @Test
    fun rejectsBindSourceOutsideApprovedDirectories() {
        val (root, proot, rootFs) = createValidRuntime()
        val outside = temporaryFolder.newFolder("outside")
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(
                ProotLaunchRequest(
                    prootExecutable = proot,
                    rootFsDirectory = rootFs,
                    rootFsArchitecture = LinuxRootFsArchitecture.ARM64,
                    guestCommand = "/bin/sh",
                    bindMounts = listOf(ProotBindMount(outside, "/mnt/outside")),
                    allowedHostRoots = listOf(root)
                )
            )
        }
    }

    @Test
    fun rejectsRootFsOutsideApprovedStorage() {
        val (root, proot, _) = createValidRuntime()
        val outside = temporaryFolder.newFolder("other-rootfs")
        File(outside, "etc").mkdirs()
        File(outside, "etc/os-release").writeText("ID=other")
        File(outside, "bin").mkdirs()
        File(outside, "bin/sh").writeText("#!/bin/sh")
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(
                ProotLaunchRequest(
                    prootExecutable = proot,
                    rootFsDirectory = outside,
                    rootFsArchitecture = LinuxRootFsArchitecture.ARM64,
                    guestCommand = "/bin/sh",
                    allowedHostRoots = listOf(root)
                )
            )
        }
    }

    @Test
    fun rejectsNonArm64RootFsForInitialAndroidHost() {
        val (root, proot, rootFs) = createValidRuntime()
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(
                ProotLaunchRequest(
                    prootExecutable = proot,
                    rootFsDirectory = rootFs,
                    rootFsArchitecture = LinuxRootFsArchitecture.X86_64,
                    guestCommand = "/bin/sh",
                    allowedHostRoots = listOf(root)
                )
            )
        }
    }

    private fun createValidRuntime(): Triple<File, File, File> {
        val root = temporaryFolder.newFolder("valid")
        val rootFs = File(root, "rootfs").apply { mkdirs() }
        File(rootFs, "etc").mkdirs()
        File(rootFs, "bin").mkdirs()
        File(rootFs, "etc/os-release").writeText("ID=ubuntu")
        File(rootFs, "bin/sh").apply {
            writeText("#!/bin/sh")
            setExecutable(true)
        }
        val proot = File(root, "proot").apply {
            writeText("binary placeholder")
            setExecutable(true)
        }
        return Triple(root, proot, rootFs)
    }
}
