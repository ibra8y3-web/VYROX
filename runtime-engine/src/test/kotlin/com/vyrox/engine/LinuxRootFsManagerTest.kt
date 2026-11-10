package com.vyrox.engine

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class LinuxRootFsManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun importsArm64TarRootFsAndPersistsMetadata() {
        val capsuleRoot = createCapsuleRoot()
        val manager = LinuxRootFsManager(capsuleRoot)
        val archive = createTarArchive(validRootFsEntries(183))

        val result = manager.importArchive(
            CAPSULE_ID,
            "ubuntu-arm64.tar",
            ByteArrayInputStream(archive)
        )

        assertEquals(LinuxRootFsArchitecture.ARM64, result.architecture)
        assertEquals("Ubuntu Test", result.prettyName)
        assertEquals(result, manager.getInstalled(CAPSULE_ID, LinuxRootFsArchitecture.ARM64))
        assertTrue(
            File(
                File(capsuleRoot, CAPSULE_ID),
                "linux/rootfs-arm64/etc/os-release"
            ).isFile
        )
    }

    @Test
    fun importsX86_64RootFsToSeparateDirectory() {
        val manager = LinuxRootFsManager(createCapsuleRoot())
        val archive = createTarArchive(validRootFsEntries(62))

        val result = manager.importArchive(
            CAPSULE_ID,
            "debian-amd64.tar",
            ByteArrayInputStream(archive)
        )

        assertEquals(LinuxRootFsArchitecture.X86_64, result.architecture)
        assertEquals(1, manager.listInstalled(CAPSULE_ID).size)
    }

    @Test
    fun supportsXzCompressedTarArchives() {
        val manager = LinuxRootFsManager(createCapsuleRoot())
        val tar = createTarArchive(validRootFsEntries(183))
        val compressed = ByteArrayOutputStream().use { output ->
            XZCompressorOutputStream(output).use { xz -> xz.write(tar) }
            output.toByteArray()
        }

        val result = manager.importArchive(
            CAPSULE_ID,
            "rootfs-arm64.tar.xz",
            ByteArrayInputStream(compressed)
        )

        assertEquals(LinuxRootFsArchitecture.ARM64, result.architecture)
    }

    @Test
    fun rejectsArchivePathTraversalAndRemovesStagingFiles() {
        val manager = LinuxRootFsManager(createCapsuleRoot())
        val entries = validRootFsEntries(183).toMutableList()
        entries += "safe/../../outside.txt" to "escape".toByteArray()
        val archive = createTarArchive(entries)

        assertThrows(IOException::class.java) {
            manager.importArchive(CAPSULE_ID, "unsafe.tar", ByteArrayInputStream(archive))
        }
        assertTrue(manager.listInstalled(CAPSULE_ID).isEmpty())
    }

    @Test
    fun rejectsRootFsWithoutOsReleaseOrSupportedShell() {
        val manager = LinuxRootFsManager(createCapsuleRoot())
        val archive = createTarArchive(listOf("etc/os-release" to "ID=empty".toByteArray()))

        assertThrows(IOException::class.java) {
            manager.importArchive(CAPSULE_ID, "incomplete.tar", ByteArrayInputStream(archive))
        }
    }

    @Test
    fun resolvesTarHardLinksAfterTheirTargetEntryAppears() {
        val capsuleRoot = createCapsuleRoot()
        val manager = LinuxRootFsManager(capsuleRoot)
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            val osRelease = "ID=ubuntu\\nVERSION_ID=24.04\\n".toByteArray()
            val osEntry = TarArchiveEntry("etc/os-release").apply { size = osRelease.size.toLong() }
            tar.putArchiveEntry(osEntry)
            tar.write(osRelease)
            tar.closeArchiveEntry()

            val hardLink = TarArchiveEntry("usr/bin/sh", TarConstants.LF_LINK).apply {
                linkName = "bin/sh"
                size = 0L
            }
            tar.putArchiveEntry(hardLink)
            tar.closeArchiveEntry()

            val binDirectory = TarArchiveEntry("bin/").apply { size = 0L }
            tar.putArchiveEntry(binDirectory)
            tar.closeArchiveEntry()

            val shell = createElfHeader(183)
            val shellEntry = TarArchiveEntry("bin/sh").apply { size = shell.size.toLong() }
            tar.putArchiveEntry(shellEntry)
            tar.write(shell)
            tar.closeArchiveEntry()
            tar.finish()
        }

        val imported = manager.importArchive(
            CAPSULE_ID,
            "hard-links.tar",
            ByteArrayInputStream(output.toByteArray())
        )
        assertEquals(LinuxRootFsArchitecture.ARM64, imported.architecture)
        val linkedShell = File(
            File(capsuleRoot, CAPSULE_ID),
            "linux/rootfs-arm64/usr/bin/sh"
        )
        assertTrue(linkedShell.isFile)
        assertTrue(linkedShell.readBytes().contentEquals(createElfHeader(183)))
    }

    @Test
    fun rejectsElf32ShellEvenWhenMachineFieldSaysAarch64() {
        val manager = LinuxRootFsManager(createCapsuleRoot())
        val entries = validRootFsEntries(183).toMutableList()
        entries[1] = "bin/sh" to createElfHeader(183).also { it[4] = 1 }
        val archive = createTarArchive(entries)

        assertThrows(IOException::class.java) {
            manager.importArchive(CAPSULE_ID, "elf32-arm64.tar", ByteArrayInputStream(archive))
        }
        assertTrue(manager.listInstalled(CAPSULE_ID).isEmpty())
    }

    @Test
    fun rejectsBigEndianElfForInitialAndroidRootFs() {
        val manager = LinuxRootFsManager(createCapsuleRoot())
        val entries = validRootFsEntries(183).toMutableList()
        entries[1] = "bin/sh" to createElfHeader(183).also { it[5] = 2 }
        val archive = createTarArchive(entries)

        assertThrows(IOException::class.java) {
            manager.importArchive(CAPSULE_ID, "big-endian.tar", ByteArrayInputStream(archive))
        }
        assertTrue(manager.listInstalled(CAPSULE_ID).isEmpty())
    }

    @Test
    fun preservesPreviousRootFsWhenNewArchiveIsInvalid() {
        val manager = LinuxRootFsManager(createCapsuleRoot())
        val first = createTarArchive(validRootFsEntries(183, "First"))
        manager.importArchive(CAPSULE_ID, "first.tar", ByteArrayInputStream(first))

        assertThrows(IOException::class.java) {
            manager.importArchive(CAPSULE_ID, "broken.tar", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }
        val current = manager.getInstalled(CAPSULE_ID, LinuxRootFsArchitecture.ARM64)
        assertEquals("First", current?.prettyName)

        val second = createTarArchive(validRootFsEntries(183, "Second"))
        val updated = manager.importArchive(CAPSULE_ID, "second.tar", ByteArrayInputStream(second))
        assertEquals("Second", updated.prettyName)
    }

    private fun createCapsuleRoot(): File {
        val root = temporaryFolder.newFolder("capsules")
        val capsule = File(root, CAPSULE_ID)
        assertTrue(capsule.mkdir())
        File(capsule, "capsule.properties").writeText("id=$CAPSULE_ID\n")
        return root
    }

    private fun validRootFsEntries(machine: Int, prettyName: String = "Ubuntu Test") = listOf(
        "etc/os-release" to "ID=ubuntu\nVERSION_ID=24.04\nPRETTY_NAME=\"$prettyName\"\n".toByteArray(),
        "bin/sh" to createElfHeader(machine)
    )

    private fun createTarArchive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            entries.forEach { (name, bytes) ->
                val entry = TarArchiveEntry(name)
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        return output.toByteArray()
    }

    private fun createElfHeader(machine: Int): ByteArray {
        val header = ByteArray(64)
        header[0] = 0x7F
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2
        header[5] = 1
        header[6] = 1
        header[18] = (machine and 0xFF).toByte()
        header[19] = ((machine ushr 8) and 0xFF).toByte()
        return header
    }

    companion object {
        private const val CAPSULE_ID = "11111111-2222-3333-4444-555555555555"
    }
}
