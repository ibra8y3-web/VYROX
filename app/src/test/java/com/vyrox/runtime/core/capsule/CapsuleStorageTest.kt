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
    fun resolvesPathsWithoutCaseAndRejectsCaseOnlyDuplicates() {
        val storage = CapsuleStorage(temporaryFolder.newFolder("capsules"))
        val capsule = storage.createCapsule("Case handling")
        storage.createDirectory(capsule.id, 'D', "Downloads", "Projects")

        assertTrue(storage.listDirectory(capsule.id, 'D', "downloads/projects").isEmpty())

        var duplicateRejected = false
        try {
            storage.createDirectory(capsule.id, 'D', "Downloads", "projects")
        } catch (_: java.io.IOException) {
            duplicateRejected = true
        }
        assertTrue(duplicateRejected)
    }

    @Test
    fun keepsUserDotFilesVisibleInTheCapsuleExplorer() {
        val storage = CapsuleStorage(temporaryFolder.newFolder("capsules"))
        val capsule = storage.createCapsule("Dot files")
        storage.createDirectory(capsule.id, 'D', "Downloads", ".config")

        assertEquals(
            listOf(".config"),
            storage.listDirectory(capsule.id, 'D', "Downloads").map { it.name }
        )
    }

    @Test
    fun renamesMovesCopiesAndSearchesCapsuleEntries() {
        val storage = CapsuleStorage(temporaryFolder.newFolder("capsules"))
        val capsule = storage.createCapsule("File operations")
        storage.createDirectory(capsule.id, 'D', "Downloads", "Projects")
        val imported = storage.importFile(
            capsule.id,
            'D',
            "Downloads/Projects",
            "build.log",
            ByteArrayInputStream("first build".toByteArray())
        )

        val renamed = storage.renameEntry(
            capsule.id,
            'D',
            imported.relativePath,
            "build-output.log"
        )
        assertEquals("Downloads/Projects/build-output.log", renamed.relativePath)

        val copied = storage.copyEntry(
            capsule.id,
            'D',
            renamed.relativePath,
            "Documents"
        )
        assertEquals("Documents/build-output.log", copied.relativePath)

        val moved = storage.moveEntry(
            capsule.id,
            'D',
            renamed.relativePath,
            "Games"
        )
        assertEquals("Games/build-output.log", moved.relativePath)

        val matches = storage.searchEntries(capsule.id, 'D', "BUILD")
        assertEquals(
            setOf("Documents/build-output.log", "Games/build-output.log"),
            matches.map { it.relativePath }.toSet()
        )
        assertTrue(storage.listDirectory(capsule.id, 'D', "Downloads/Projects").isEmpty())
    }

    @Test
    fun rejectsMovingOrCopyingFolderIntoItself() {
        val storage = CapsuleStorage(temporaryFolder.newFolder("capsules"))
        val capsule = storage.createCapsule("No recursive loop")
        storage.createDirectory(capsule.id, 'D', "Downloads", "Projects")

        var moveRejected = false
        try {
            storage.moveEntry(capsule.id, 'D', "Downloads/Projects", "Downloads/Projects")
        } catch (_: IllegalArgumentException) {
            moveRejected = true
        }
        assertTrue(moveRejected)

        var copyRejected = false
        try {
            storage.copyEntry(capsule.id, 'D', "Downloads/Projects", "Downloads/Projects")
        } catch (_: IllegalArgumentException) {
            copyRejected = true
        }
        assertTrue(copyRejected)
    }

    @Test
    fun rejectsCaseInsensitiveRenameCollision() {
        val storage = CapsuleStorage(temporaryFolder.newFolder("capsules"))
        val capsule = storage.createCapsule("Rename collision")
        storage.createDirectory(capsule.id, 'D', "Downloads", "One")
        storage.createDirectory(capsule.id, 'D', "Downloads", "Two")

        var duplicateRejected = false
        try {
            storage.renameEntry(capsule.id, 'D', "Downloads/Two", "oNE")
        } catch (_: java.io.IOException) {
            duplicateRejected = true
        }
        assertTrue(duplicateRejected)
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
