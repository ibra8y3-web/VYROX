package com.vyrox.runtime.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerPolicyTest {
    @Test
    fun acceptsExeMsiAndZipExtensionsCaseInsensitively() {
        assertTrue(InstallerPolicy.isSupportedInstaller("setup.exe"))
        assertTrue(InstallerPolicy.isSupportedInstaller("package.MSI"))
        assertTrue(InstallerPolicy.isSupportedInstaller("portable.ZIP"))
    }

    @Test
    fun rejectsUnsupportedAndMissingExtensions() {
        assertFalse(InstallerPolicy.isSupportedInstaller("readme.txt"))
        assertFalse(InstallerPolicy.isSupportedInstaller("installer"))
        assertFalse(InstallerPolicy.isSupportedInstaller("game.apk"))
        assertFalse(InstallerPolicy.isSupportedInstaller("malware.zipx"))
    }

    @Test
    fun sanitizesPathSeparatorsAndUnsafeCharacters() {
        assertEquals(
            "setup_file.exe",
            InstallerPolicy.sanitizeFileName("../folder/setup file.exe")
        )
        assertEquals(
            "legacy_setup.msi",
            InstallerPolicy.sanitizeFileName("C:\\downloads\\legacy setup.msi")
        )
    }

    @Test
    fun keepsAnEmptyNameSafe() {
        assertEquals("installer", InstallerPolicy.sanitizeFileName("..."))
    }
}
