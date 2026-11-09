package com.vyrox.runtime.core.capsule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
    fun ignoresDirectoriesWithoutValidMetadata() {
        val root = temporaryFolder.newFolder("capsules")
        File(root, "not-a-capsule-id").mkdirs()
        val storage = CapsuleStorage(root)

        assertTrue(storage.listCapsules().isEmpty())
    }
}
