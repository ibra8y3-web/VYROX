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
import java.util.UUID

class WinePrefixManagerTest {
    private fun newRootFs(): File {
        val root = Files.createTempDirectory("vyrox-wine-prefix-rootfs").toFile()
        File(root, "etc").mkdirs()
        File(root, "etc/os-release").writeText("ID=debian\n")
        return root
    }

    @Test
    fun preparingAPrefixCreatesOnlyAnEmptyStagingDirectory() {
        val root = newRootFs()
        try {
            val manager = WinePrefixManager(root)
            val ticket = manager.prepareCreation(UUID.randomUUID().toString())

            assertTrue(ticket.stagingDirectory.isDirectory)
            assertTrue(ticket.guestPrefixPath.startsWith("/opt/vyrox/prefixes/.staging-"))
            assertFalse(File(ticket.stagingDirectory, "system.reg").exists())
            assertFalse(File(ticket.stagingDirectory, "user.reg").exists())
            assertNull(manager.inspect(ticket.capsuleId))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedWinebootCannotCreateAReadyPrefix() {
        val root = newRootFs()
        try {
            val manager = WinePrefixManager(root)
            val ticket = manager.prepareCreation(UUID.randomUUID().toString())

            assertThrows(java.io.IOException::class.java) {
                manager.finalizeCreation(ticket, 1, "wine-11.0")
            }
            assertNull(manager.inspect(ticket.capsuleId))
            assertTrue(manager.abortCreation(ticket))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun finalizationRequiresRealWineRegistryAndDriveLinks() {
        val root = newRootFs()
        try {
            val manager = WinePrefixManager(root)
            val ticket = manager.prepareCreation(UUID.randomUUID().toString())
            createWinebootFixture(ticket.stagingDirectory)

            val info = manager.finalizeCreation(ticket, 0, "wine-11.0")

            assertEquals(ticket.capsuleId, info.capsuleId)
            assertEquals("11.0", info.wineVersion)
            assertEquals(WinePrefixManager.PREFIX_ARCHITECTURE, info.architecture)
            assertEquals(ticket.guestDriveCPath, info.guestDriveCPath)
            assertEquals(ticket.guestDriveDPath, info.guestDriveDPath)
            assertNotNull(manager.inspect(ticket.capsuleId))
            val links = File(info.directory, "dosdevices")
            assertEquals(ticket.guestDriveCPath, Files.readSymbolicLink(File(links, "c:").toPath()).toString())
            assertEquals(ticket.guestDriveDPath, Files.readSymbolicLink(File(links, "d:").toPath()).toString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun finalizationRejectsMissingRegistryAndOldWineVersions() {
        val root = newRootFs()
        try {
            val manager = WinePrefixManager(root)
            val missingRegistry = manager.prepareCreation(UUID.randomUUID().toString())
            File(missingRegistry.stagingDirectory, "drive_c").mkdirs()
            val dos = File(missingRegistry.stagingDirectory, "dosdevices")
            dos.mkdirs()
            Files.createSymbolicLink(File(dos, "c:").toPath(), Paths.get("../drive_c"))
            Files.createSymbolicLink(File(dos, "z:").toPath(), Paths.get("/"))

            assertThrows(java.io.IOException::class.java) {
                manager.finalizeCreation(missingRegistry, 0, "wine-11.0")
            }
            manager.abortCreation(missingRegistry)

            val oldWine = manager.prepareCreation(UUID.randomUUID().toString())
            createWinebootFixture(oldWine.stagingDirectory)
            assertThrows(IllegalArgumentException::class.java) {
                manager.finalizeCreation(oldWine, 0, "wine-10.0")
            }
            manager.abortCreation(oldWine)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsSymbolicLinkStagingTicketBeforeCanonicalResolution() {
        val root = newRootFs()
        try {
            val manager = WinePrefixManager(root)
            val ticket = manager.prepareCreation(UUID.randomUUID().toString())
            val alias = File(ticket.stagingDirectory.parentFile, ".staging-" + ticket.capsuleId + "-alias")
            Files.createSymbolicLink(alias.toPath(), ticket.stagingDirectory.toPath())
            val forged = WinePrefixCreationTicket(
                ticket.capsuleId,
                alias,
                ticket.guestPrefixPath,
                ticket.guestDriveCPath,
                ticket.guestDriveDPath
            )

            assertThrows(SecurityException::class.java) {
                manager.finalizeCreation(forged, 0, "wine-11.0")
            }
            assertTrue(manager.abortCreation(ticket))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesToOverwriteAnExistingPrefix() {
        val root = newRootFs()
        try {
            val manager = WinePrefixManager(root)
            val ticket = manager.prepareCreation(UUID.randomUUID().toString())
            createWinebootFixture(ticket.stagingDirectory)
            manager.finalizeCreation(ticket, 0, "wine-11.0")

            assertThrows(java.io.IOException::class.java) {
                manager.prepareCreation(ticket.capsuleId)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsAStagingTicketWithAnEscapedPath() {
        val root = newRootFs()
        val outside = Files.createTempDirectory("vyrox-wine-prefix-outside").toFile()
        try {
            val manager = WinePrefixManager(root)
            val ticket = manager.prepareCreation(UUID.randomUUID().toString())
            val forged = WinePrefixCreationTicket(
                ticket.capsuleId,
                outside,
                ticket.guestPrefixPath,
                ticket.guestDriveCPath,
                ticket.guestDriveDPath
            )

            assertThrows(SecurityException::class.java) {
                manager.finalizeCreation(forged, 0, "wine-11.0")
            }
            manager.abortCreation(ticket)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    private fun createWinebootFixture(directory: File) {
        File(directory, "drive_c").mkdirs()
        File(directory, "dosdevices").mkdirs()
        File(directory, "system.reg").writeText("WINE REGISTRY Version 2\n[Software\\Wine] 123\n")
        File(directory, "user.reg").writeText("WINE REGISTRY Version 2\n[Software\\Wine] 123\n")
        val dos = File(directory, "dosdevices")
        Files.createSymbolicLink(File(dos, "c:").toPath(), Paths.get("../drive_c"))
        Files.createSymbolicLink(File(dos, "z:").toPath(), Paths.get("/"))
    }
}
