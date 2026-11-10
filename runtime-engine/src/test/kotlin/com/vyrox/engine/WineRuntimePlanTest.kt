package com.vyrox.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WineRuntimePlanTest {
    @Test
    fun installScriptUsesOfficialWineHqAmd64RepositoryAndNewWow64Validation() {
        val script = WineRuntimePlan.createInstallShellScript()

        assertTrue(script.contains(WineRuntimePlan.WINEHQ_KEY_URL))
        assertTrue(script.contains("Architectures: amd64"))
        assertTrue(script.contains("winehq-stable"))
        assertTrue(script.contains("dpkg --add-architecture amd64"))
        assertTrue(script.contains("BOX64_LD_LIBRARY_PATH"))
        assertTrue(script.contains("VYROX_WINE_READY"))
        assertTrue(script.contains("WINE_MAJOR"))
        assertFalse(script.contains("wine32"))
        assertFalse(script.contains("WINEARCH=win32"))
    }

    @Test
    fun prefixScriptRunsRealWinebootAndChecksRealRegistryFiles() {
        val path = "/opt/vyrox/prefixes/.staging-123e4567-e89b-12d3-a456-426614174000-123e4567-e89b-12d3-a456-426614174000"
        val script = WineRuntimePlan.createPrefixShellScript(path)

        assertTrue(script.contains("WINEPREFIX='" + path + "'"))
        assertTrue(script.contains("WINEARCH=win64"))
        assertTrue(script.contains("wineboot"))
        assertTrue(script.contains("-u"))
        assertTrue(script.contains("system.reg"))
        assertTrue(script.contains("user.reg"))
        assertTrue(script.contains("VYROX_WINEBOOT_OK"))
    }

    @Test
    fun prefixScriptRejectsPathsOutsideVYROXStagingDirectory() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WineRuntimePlan.createPrefixShellScript("/tmp/not-a-vyrox-prefix")
        }
    }

    @Test
    fun wineInstallationRequiresMetadataBox64AndX8664WineBinaries() {
        val root = Files.createTempDirectory("vyrox-wine-runtime-test").toFile()
        try {
            assertFalse(WineRuntimePlan.isInstalled(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
