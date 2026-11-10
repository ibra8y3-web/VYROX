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
        val rootFs = createRootFs(capsule)
        val proot = File(root, "proot").also(::writeArm64Elf)
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
        assertTrue(plan.environment["PATH"].orEmpty().split(':').all { it.startsWith("/") })
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
    fun rejectsRootFsWithoutVYROXImportMetadata() {
        val (root, proot, rootFs) = createValidRuntime()
        File(rootFs, ".vyrox-rootfs.properties").delete()
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(request(root, proot, rootFs))
        }
    }

    @Test
    fun rejectsBigEndianProotAndRootFsBinaries() {
        val root = temporaryFolder.newFolder("big-endian-runtime")
        val proot = File(root, "proot")
        writeElf(proot, 183, littleEndian = false)
        val rootFs = createRootFs(root)
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(request(root, proot, rootFs))
        }
    }

    @Test
    fun rejectsDuplicateGuestBindTargets() {
        val (root, proot, rootFs) = createValidRuntime()
        val first = File(root, "first").apply { mkdirs() }
        val second = File(root, "second").apply { mkdirs() }
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(
                request(root, proot, rootFs).copy(
                    bindMounts = listOf(
                        ProotBindMount(first, "/mnt/shared"),
                        ProotBindMount(second, "/mnt/shared")
                    )
                )
            )
        }
    }

    @Test
    fun rejectsNonArm64ProotElf() {
        val root = temporaryFolder.newFolder("x86-proot")
        val proot = File(root, "proot")
        writeElf(proot, 62)
        val rootFs = createRootFs(root)

        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(request(root, proot, rootFs))
        }
    }

    @Test
    fun rejectsNonArm64RootFsShell() {
        val root = temporaryFolder.newFolder("x86-rootfs")
        val proot = File(root, "proot").also(::writeArm64Elf)
        val rootFs = createRootFs(root, shellMachine = 62)

        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(request(root, proot, rootFs))
        }
    }

    @Test
    fun rejectsEnvironmentVariablesThatCanInjectHostLibraries() {
        val (root, proot, rootFs) = createValidRuntime()
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(
                request(root, proot, rootFs).copy(
                    environment = mapOf("LD_PRELOAD" to "/tmp/evil.so")
                )
            )
        }
    }

    @Test
    fun rejectsRelativeOrTraversingGuestPathEnvironmentValues() {
        val (root, proot, rootFs) = createValidRuntime()
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(
                request(root, proot, rootFs).copy(
                    environment = mapOf("PATH" to "/usr/bin:../../outside")
                )
            )
        }
    }

    @Test
    fun rejectsGuestPathTraversalAndMissingCommand() {
        val (root, proot, rootFs) = createValidRuntime()
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(
                request(root, proot, rootFs).copy(guestCommand = "/../../outside")
            )
        }
    }

    @Test
    fun rejectsBindSourceOutsideApprovedDirectories() {
        val (root, proot, rootFs) = createValidRuntime()
        val outside = temporaryFolder.newFolder("outside")
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(
                request(root, proot, rootFs).copy(
                    bindMounts = listOf(ProotBindMount(outside, "/mnt/outside"))
                )
            )
        }
    }

    @Test
    fun rejectsRootFsOutsideApprovedStorage() {
        val (root, proot, _) = createValidRuntime()
        val outside = temporaryFolder.newFolder("other-rootfs")
        createRootFs(outside)
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(request(root, proot, outside))
        }
    }

    @Test
    fun rejectsNonArm64RootFsForInitialAndroidHost() {
        val (root, proot, rootFs) = createValidRuntime()
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommandPlanner.build(
                request(root, proot, rootFs).copy(
                    rootFsArchitecture = LinuxRootFsArchitecture.X86_64
                )
            )
        }
    }

    private fun request(root: File, proot: File, rootFs: File) = ProotLaunchRequest(
        prootExecutable = proot,
        rootFsDirectory = rootFs,
        rootFsArchitecture = LinuxRootFsArchitecture.ARM64,
        guestCommand = "/bin/sh",
        allowedHostRoots = listOf(root)
    )

    private fun createValidRuntime(): Triple<File, File, File> {
        val root = temporaryFolder.newFolder("valid")
        val proot = File(root, "proot").also(::writeArm64Elf)
        val rootFs = createRootFs(root)
        return Triple(root, proot, rootFs)
    }

    private fun createRootFs(root: File, shellMachine: Int = 183): File {
        val rootFs = File(root, "rootfs").apply { mkdirs() }
        File(rootFs, "etc").mkdirs()
        File(rootFs, "bin").mkdirs()
        File(rootFs, "etc/os-release").writeText("ID=ubuntu")
        File(rootFs, ".vyrox-rootfs.properties").writeText(
            "architecture=ARM64\\n" +
                "archiveSha256=" + "f".repeat(64) + "\\n" +
                "expandedBytes=4096\\nentryCount=3\\ninstalledAtEpochMillis=1\\n"
        )
        writeElf(File(rootFs, "bin/sh"), shellMachine)
        return rootFs
    }

    private fun writeArm64Elf(file: File) = writeElf(file, 183)

    private fun writeElf(file: File, machine: Int, littleEndian: Boolean = true) {
        file.parentFile?.mkdirs()
        val header = ByteArray(64)
        header[0] = 0x7F.toByte()
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2
        header[5] = 1
        header[6] = 1
        if (littleEndian) {
            header[18] = (machine and 0xFF).toByte()
            header[19] = ((machine ushr 8) and 0xFF).toByte()
        } else {
            header[18] = ((machine ushr 8) and 0xFF).toByte()
            header[19] = (machine and 0xFF).toByte()
            header[5] = 2
        }
        file.writeBytes(header)
        file.setExecutable(true)
    }
}
