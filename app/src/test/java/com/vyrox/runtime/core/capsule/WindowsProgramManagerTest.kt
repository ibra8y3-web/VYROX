package com.vyrox.runtime.core.capsule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WindowsProgramManagerTest {
    @Test
    fun discoversExecutablesOnlyInsideProgramFoldersAndAttachesExtractedIcons() {
        val root = Files.createTempDirectory("vyrox-program-manager").toFile()
        try {
            val storage = CapsuleStorage(File(root, "capsules"))
            val capsule = storage.createCapsule("Test")
            val driveC = storage.getDriveRoot(capsule.id, 'C')!!
            val chrome = File(driveC, "Program Files/Browser/Chrome.exe")
            check(chrome.parentFile.mkdirs())
            chrome.writeBytes(byteArrayOf(0x4D, 0x5A))
            val uninstaller = File(chrome.parentFile, "uninstall.exe")
            uninstaller.writeBytes(byteArrayOf(0x4D, 0x5A))
            val windowsTool = File(driveC, "Windows/System32/notepad.exe")
            check(windowsTool.parentFile.mkdirs())
            windowsTool.writeBytes(byteArrayOf(0x4D, 0x5A))
            val icons = File(driveC, "VYROX/Icons")
            check(icons.mkdirs())
            val icon = File(icons, "Chrome.exe_14_1_1_48x48x32.png")
            icon.writeBytes(byteArrayOf(1, 2, 3))

            val programs = WindowsProgramManager(storage).discover(capsule.id)

            assertEquals(1, programs.size)
            assertEquals("Chrome", programs.single().name)
            assertEquals("Program Files/Browser/Chrome.exe", programs.single().relativePath)
            assertEquals(icon.canonicalFile, programs.single().iconFile)
            assertTrue(programs.single().id.isNotBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ignoresSymlinksAndReturnsEmptyWhenCapsuleDoesNotExist() {
        val root = Files.createTempDirectory("vyrox-program-manager-safe").toFile()
        try {
            val storage = CapsuleStorage(File(root, "capsules"))
            assertTrue(WindowsProgramManager(storage).discover("00000000-0000-0000-0000-000000000000").isEmpty())

            val capsule = storage.createCapsule("Test")
            val driveC = storage.getDriveRoot(capsule.id, 'C')!!
            val outside = File(root, "outside.exe").apply { writeBytes(byteArrayOf(0x4D, 0x5A)) }
            val folder = File(driveC, "Program Files/Linked")
            check(folder.mkdirs())
            Files.createSymbolicLink(File(folder, "outside.exe").toPath(), outside.toPath())

            assertTrue(WindowsProgramManager(storage).discover(capsule.id).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
