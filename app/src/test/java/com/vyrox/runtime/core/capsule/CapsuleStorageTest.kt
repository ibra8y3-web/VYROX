package com.vyrox.runtime.core.capsule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class CapsuleStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createsAndRestoresCapsuleMetadata() {
        val root = temporaryFolder.newFolder("capsules")
        val storage = CapsuleStorage(root)

        val created = storage.createCapsule("  My   Workspace  ")

        assertEquals("My Workspace", created.name)
        assertEquals(listOf(created), storage.listCapsules())
        assertTrue(File(root, created.id).isDirectory)
        val cDrive = requireNotNull(storage.getDriveRoot(created.id, 'C'))
        val dDrive = requireNotNull(storage.getDriveRoot(created.id, 'D'))
        assertTrue(File(cDrive, "Windows").isDirectory)
        assertTrue(File(cDrive, "Program Files").isDirectory)
        assertTrue(File(cDrive, "Users/Default").isDirectory)
        assertTrue(File(dDrive, "Downloads").isDirectory)
        assertTrue(File(dDrive, "Games").isDirectory)
    }

    @Test
    fun deletesOnlyTheRequestedCapsule() {
        val storage = CapsuleStorage(temporaryFolder.newFolder("capsules"))
        val first = storage.createCapsule("First")
        val second = storage.createCapsule("Second")

        assertTrue(storage.deleteCapsule(first.id))
        assertFalse(storage.deleteCapsule(first.id))
        assertEquals(listOf(second), storage.listCapsules())
        assertFalse(storage.deleteCapsule("../Second"))
    }

    @Test
    fun rejectsUnsupportedDriveLettersAndInvalidCapsuleIds() {
        val storage = CapsuleStorage(temporaryFolder.newFolder("capsules"))
        val capsule = storage.createCapsule("Workspace")

        assertNull(storage.getDriveRoot(capsule.id, 'E'))
        assertNull(storage.getDriveRoot("../outside", 'C'))
    }

    @Test
    fun createsListsImportsAndDeletesCapsuleFiles() {
        val storage = CapsuleStorage(temporaryFolder.newFolder("capsules"))
        val capsule = storage.createCapsule("Files")
        val nested = storage.createDirectory(
            capsule.id,
            'D',
            "Downloads",
            "Documents"
        )

        val imported = storage.importFile(
            capsule.id,
            'D',
            nested.relativePath,
            "notes.txt",
            ByteArrayInputStream("VYROX".toByteArray())
        )

        assertEquals("Downloads/Documents/notes.txt", imported.relativePath)
        assertEquals(
            listOf("notes.txt"),
            storage.listDirectory(capsule.id, 'D', "Downloads/Documents").map { it.name }
        )
        assertTrue(storage.deleteEntry(capsule.id, 'D', imported.relativePath))
        assertTrue(storage.listDirectory(capsule.id, 'D', "Downloads/Documents").isEmpty())
        assertFalse(storage.deleteEntry(capsule.id, 'D', ""))
    }

    @Test
    fun rejectsCapsuleFilePathTraversalAndWindowsInvalidNames() {
        val storage = CapsuleStorage(temporaryFolder.newFolder("capsules"))
        val capsule = storage.createCapsule("Safe paths")

        var traversalRejected = false
        try {
            storage.listDirectory(capsule.id, 'C', "../outside")
        } catch (_: IllegalArgumentException) {
            traversalRejected = true
        }
        assertTrue(traversalRejected)

        var nameRejected = false
        try {
            storage.createDirectory(capsule.id, 'C', "", "../outside")
        } catch (_: IllegalArgumentException) {
            nameRejected = true
        }
        assertTrue(nameRejected)
    }

    @Test
    fun ignoresDirectoriesWithoutValidMetadata() {
        val root = temporaryFolder.newFolder("capsules")
        File(root, "not-a-capsule-id").mkdirs()
        val storage = CapsuleStorage(root)

        assertTrue(storage.listCapsules().isEmpty())
    }
}
