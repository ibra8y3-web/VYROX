package com.vyrox.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class WinePrefixManagerTest {
    private val testCapsuleId = "123e4567-e89b-12d3-a456-426614174000"

    private fun newRootFs(): File {
        val workspace = Files.createTempDirectory("vyrox-wine-prefix-rootfs").toFile()
        val capsule = File(workspace, testCapsuleId)
        check(capsule.mkdirs())
        File(capsule, "capsule.properties").writeText("id=$testCapsuleId\n")
        check(File(capsule, "drive_c").mkdir())
        check(File(capsule, "drive_d").mkdir())
        val linux = File(capsule, "linux")
        check(linux.mkdir())
        val root = File(linux, "rootfs-arm64")
        check(root.mkdirs())
        File(root, "etc").mkdirs()
        File(root, "etc/os-release").writeText("ID=debian\n")
        return root
    }

    private fun cleanupRootFs(root: File) {
        root.parentFile?.parentFile?.parentFile?.deleteRecursively()
    }

    @Test
    fun preparingAPrefixCreatesOnlyAnEmptyStagingDirectory() {
        val root = newRootFs()
        try {
            val manager = WinePrefixManager(root)
            val ticket = manager.prepareCreation(testCapsuleId)

            assertTrue(ticket.stagingDirectory.isDirectory)
            assertTrue(ticket.guestPrefixPath.startsWith("/opt/vyrox/prefixes/.staging-"))
            assertFalse(File(ticket.stagingDirectory, "system.reg").exists())
            assertFalse(File(ticket.stagingDirectory, "user.reg").exists())
            assertNull(manager.inspect(ticket.capsuleId))
        } finally {
            cleanupRootFs(root)
        }
    }

    @Test
    fun failedWinebootCannotCreateAReadyPrefix() {
        val root = newRootFs()
        try {
            val manager = WinePrefixManager(root)
            val ticket = manager.prepareCreation(testCapsuleId)

            assertThrows(java.io.IOException::class.java) {
                manager.finalizeCreation(ticket, 1, "wine-11.0")
            }
            assertNull(manager.inspect(ticket.capsuleId))
            assertTrue(manager.abortCreation(ticket))
        } finally {
            cleanupRootFs(root)
        }
    }

    @Test
    fun finalizationRequiresRealWineRegistryAndDriveLinks() {
        val root = newRootFs()
        try {
            val manager = WinePrefixManager(root)
            val ticket = manager.prepareCreation(testCapsuleId)
            createWinebootFixture(ticket)

            val info = manager.finalizeCreation(ticket, 0, "wine-11.0")

            assertEquals(ticket.capsuleId, info.capsuleId)
            assertEquals("11.0", info.wineVersion)
            assertEquals(WinePrefixManager.PREFIX_ARCHITECTURE, info.architecture)
            assertEquals(ticket.guestDriveCPath, info.guestDriveCPath)
            assertEquals(ticket.guestDriveDPath, info.guestDriveDPath)
            assertNotNull(manager.inspect(ticket.capsuleId))
            val links = File(info.directory, "dosdevices")
            assertEquals("../drive_c", Files.readSymbolicLink(File(links, "c:").toPath()).toString())
            assertEquals(ticket.guestDriveDPath, Files.readSymbolicLink(File(links, "d:").toPath()).toString())
            assertEquals(ticket.guestDriveCPath, Files.readSymbolicLink(File(info.directory, "drive_c").toPath()).toString())
        } finally {
            cleanupRootFs(root)
        }
    }

    @Test
    fun finalizationRejectsMissingRegistryAndOldWineVersions() {
        val root = newRootFs()
        try {
            val manager = WinePrefixManager(root)
            val missingRegistry = manager.prepareCreation(testCapsuleId)
            val dos = File(missingRegistry.stagingDirectory, "dosdevices")
            dos.mkdirs()
            Files.createSymbolicLink(File(dos, "c:").toPath(), Paths.get("../drive_c"))
            Files.createSymbolicLink(File(dos, "z:").toPath(), Paths.get("/"))

            assertThrows(java.io.IOException::class.java) {
                manager.finalizeCreation(missingRegistry, 0, "wine-11.0")
            }
            manager.abortCreation(missingRegistry)

            val oldWine = manager.prepareCreation(testCapsuleId)
            createWinebootFixture(oldWine)
            assertThrows(IllegalArgumentException::class.java) {
                manager.finalizeCreation(oldWine, 0, "wine-10.0")
            }
            manager.abortCreation(oldWine)
        } finally {
            cleanupRootFs(root)
        }
    }

    @Test
    fun rejectsSymbolicLinkStagingTicketBeforeCanonicalResolution() {
        val root = newRootFs()
        try {
            val manager = WinePrefixManager(root)
            val ticket = manager.prepareCreation(testCapsuleId)
            val alias = File(ticket.stagingDirectory.parentFile, ".staging-" + ticket.capsuleId + "-alias")
            Files.createSymbolicLink(alias.toPath(), ticket.stagingDirectory.toPath())
            val forged = WinePrefixCreationTicket(
                ticket.capsuleId,
                alias,
                ticket.guestPrefixPath,
                ticket.guestDriveCPath,
                ticket.guestDriveDPath,
                ticket.hostDriveCDirectory,
                ticket.hostDriveDDirectory
            )

            assertThrows(SecurityException::class.java) {
                manager.finalizeCreation(forged, 0, "wine-11.0")
            }
            assertTrue(manager.abortCreation(ticket))
        } finally {
            cleanupRootFs(root)
        }
    }

    @Test
    fun refusesToOverwriteAnExistingPrefix() {
        val root = newRootFs()
        try {
            val manager = WinePrefixManager(root)
            val ticket = manager.prepareCreation(testCapsuleId)
            createWinebootFixture(ticket)
            manager.finalizeCreation(ticket, 0, "wine-11.0")

            assertThrows(java.io.IOException::class.java) {
                manager.prepareCreation(ticket.capsuleId)
            }
        } finally {
            cleanupRootFs(root)
        }
    }

    @Test
    fun rejectsAStagingTicketWithAnEscapedPath() {
        val root = newRootFs()
        val outside = Files.createTempDirectory("vyrox-wine-prefix-outside").toFile()
        try {
            val manager = WinePrefixManager(root)
            val ticket = manager.prepareCreation(testCapsuleId)
            val forged = WinePrefixCreationTicket(
                ticket.capsuleId,
                outside,
                ticket.guestPrefixPath,
                ticket.guestDriveCPath,
                ticket.guestDriveDPath,
                ticket.hostDriveCDirectory,
                ticket.hostDriveDDirectory
            )

            assertThrows(SecurityException::class.java) {
                manager.finalizeCreation(forged, 0, "wine-11.0")
            }
            manager.abortCreation(ticket)
        } finally {
            cleanupRootFs(root)
            outside.deleteRecursively()
        }
    }

    private fun createWinebootFixture(ticket: WinePrefixCreationTicket) {
        val directory = ticket.stagingDirectory
        val windows = File(ticket.hostDriveCDirectory, "windows/System32")
        check(windows.mkdirs())
        File(windows, "kernel32.dll").writeText("wineboot-kernel32")
        File(directory, "dosdevices").mkdirs()
        File(directory, "system.reg").writeText("WINE REGISTRY Version 2\n[Software\\Wine] 123\n")
        File(directory, "user.reg").writeText("WINE REGISTRY Version 2\n[Software\\Wine] 123\n")
        val dos = File(directory, "dosdevices")
        Files.createSymbolicLink(File(dos, "c:").toPath(), Paths.get("../drive_c"))
        Files.createSymbolicLink(File(dos, "z:").toPath(), Paths.get("/"))
    }
}